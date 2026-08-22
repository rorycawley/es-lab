(ns lab29.websub.hub
  "Subscriptions, verification of intent, leases and distribution.

  The trust model inverts at this boundary and every mechanism here is a
  consequence of that. Internally, a module receives a message because the
  dispatcher routed it there; the inbox worries about duplicates, not about
  whether the sender is entitled to send. Here the hub is handed a callback
  URL by an unauthenticated stranger and asked to POST to it, repeatedly,
  forever.

  So:

  | it might not be their URL | verify intent before the first delivery |
  | they might stop existing  | leases expire rather than run forever   |
  | anyone can POST to them   | sign every body with *their* secret     |

  Verification is the important one. Without it, `hub.callback` is an
  amplification weapon: subscribe somebody else's server to a busy topic and
  the hub becomes the attacker."
  (:require [clojure.string :as str]
            [lab29.platform.resilience :as resilience]
            [lab29.websub.signature :as signature]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpRequest$Builder HttpResponse$BodyHandlers)
           (java.time Duration Instant OffsetDateTime ZoneOffset)))

(defn- ->timestamptz
  "An `Instant` the JDBC driver will accept for a `timestamptz` column.

  The clock deals in instants because a lease is a moment in time and not a
  moment in a timezone. The driver wants an offset, so the conversion happens
  here rather than leaking a database type into the clock."
  [^Instant instant]
  (OffsetDateTime/ofInstant instant ZoneOffset/UTC))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(def max-lease-seconds 86400)

;; ---------------------------------------------------------------------------
;; Talking to a stranger
;; ---------------------------------------------------------------------------

(defn- get! [^HttpClient client url]
  (try
    (let [response (.send client
                          (-> (HttpRequest/newBuilder (URI/create url))
                              (.timeout (Duration/ofSeconds 5))
                              (.GET)
                              (.build))
                          (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode response) :body (.body response)})
    (catch java.io.IOException e
      (throw (ex-info "Subscriber callback unreachable"
                      {:reason :subscriber-unreachable} e)))
    (catch InterruptedException e
      (throw (ex-info "Interrupted calling subscriber"
                      {:reason :subscriber-unreachable} e)))))

(defn- post! [^HttpClient client url headers body]
  (try
    (let [request (reduce-kv (fn [^HttpRequest$Builder b k v] (.header b k v))
                             (-> (HttpRequest/newBuilder (URI/create url))
                                 (.timeout (Duration/ofSeconds 5)))
                             headers)
          response (.send client
                          (.build (.POST request (HttpRequest$BodyPublishers/ofString body)))
                          (HttpResponse$BodyHandlers/ofString))]
      (if (<= 200 (.statusCode response) 299)
        {:status (.statusCode response)}
        (throw (ex-info "Subscriber refused a delivery"
                        {:reason :subscriber-refused :status (.statusCode response)}))))
    (catch java.io.IOException e
      (throw (ex-info "Subscriber callback unreachable"
                      {:reason :subscriber-unreachable} e)))
    (catch InterruptedException e
      (throw (ex-info "Interrupted calling subscriber"
                      {:reason :subscriber-unreachable} e)))))

;; ---------------------------------------------------------------------------
;; Subscription lifecycle
;; ---------------------------------------------------------------------------

(defn- query-string [params]
  (str/join
   "&"
   (for [[k v] params]
     (str (java.net.URLEncoder/encode (str k) "UTF-8") "="
          (java.net.URLEncoder/encode (str v) "UTF-8")))))

(defn- verify-intent!
  "Ask the callback whether it really asked for this.

  A subscription exists only if the far end echoes our challenge. Anything
  else -- a non-2xx, a different body, an unreachable host -- and we simply do
  not create it, because the request may not have come from whoever owns that
  URL."
  [{:keys [client new-id]} {:keys [mode topic callback lease-seconds]}]
  (let [challenge (str (new-id))
        url       (str callback (if (str/includes? callback "?") "&" "?")
                       (query-string {"hub.mode" (name mode)
                                      "hub.topic" topic
                                      "hub.challenge" challenge
                                      "hub.lease_seconds" lease-seconds}))
        {:keys [status body]} (get! client url)]
    (and (<= 200 status 299) (= challenge (str/trim (or body ""))))))

(defn subscribe!
  "Handle `hub.mode=subscribe`.

  Returns `{:accepted …}` once the callback has proved intent, or
  `{:rejected …}`. The W3C flow answers 202 first and verifies out of band;
  this verifies inline so a test can assert the outcome without racing, and
  the README says so rather than pretending otherwise."
  [{:keys [datasource new-id clock] :as hub}
   {:keys [topic callback secret lease-seconds]}]
  (let [lease (min (or lease-seconds max-lease-seconds) max-lease-seconds)]
    (if-not (verify-intent! hub {:mode :subscribe :topic topic :callback callback
                                 :lease-seconds lease})
      {:rejected :verification-failed}
      (let [now (clock)]
        (jdbc/execute-one!
         datasource
         ["INSERT INTO websub.subscription
             (subscription_id, topic, callback, secret, lease_seconds,
              requested_at, verified_at, expires_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT (topic, callback) DO UPDATE
             SET secret = EXCLUDED.secret,
                 lease_seconds = EXCLUDED.lease_seconds,
                 requested_at = EXCLUDED.requested_at,
                 verified_at = EXCLUDED.verified_at,
                 expires_at = EXCLUDED.expires_at,
                 attempts = 0, last_error = NULL"
          (new-id) topic callback secret lease
          (->timestamptz now) (->timestamptz now)
          (->timestamptz (Instant/ofEpochSecond (+ (.getEpochSecond ^Instant now) lease)))])
        {:accepted {:topic topic :callback callback :lease-seconds lease}}))))

(defn unsubscribe!
  "Handle `hub.mode=unsubscribe`. Verified too, for the same reason: nobody
  else gets to cancel your subscription either."
  [{:keys [datasource] :as hub} {:keys [topic callback]}]
  (if-not (verify-intent! hub {:mode :unsubscribe :topic topic :callback callback
                               :lease-seconds 0})
    {:rejected :verification-failed}
    (do (jdbc/execute-one!
         datasource
         ["DELETE FROM websub.subscription WHERE topic = ? AND callback = ?"
          topic callback])
        {:accepted {:topic topic :callback callback}})))

(defn live
  "Verified, unexpired subscriptions to a topic."
  [{:keys [datasource clock]} topic]
  (jdbc/execute!
   datasource
   ["SELECT subscription_id, topic, callback, secret
       FROM websub.subscription
      WHERE topic = ? AND verified_at IS NOT NULL AND expires_at > ?
      ORDER BY callback"
    topic (->timestamptz (clock))]
   opts))

;; ---------------------------------------------------------------------------
;; Distribution
;; ---------------------------------------------------------------------------

(defn distribute!
  "Push one topic's current representation to everyone entitled to it.

  Reuses lab 28's retry and breaker, because a subscriber's server is exactly
  the kind of thing that is briefly unavailable. A subscription that keeps
  refusing is dropped rather than retried forever -- the hub's version of a
  dead letter, and the reason leases exist at all."
  [{:keys [datasource policy hub-url] :as hub} topic body]
  (mapv
   (fn [{:keys [subscription-id callback secret]}]
     (try
       (resilience/call!
        policy
        (fn []
          (post! (:client hub) callback
                 (cond-> {"Content-Type" "application/json"
                          "Link" (str "<" topic ">; rel=\"self\", <" hub-url ">; rel=\"hub\"")}
                   secret (assoc "X-Hub-Signature" (signature/sign secret body)))
                 body)))
       (jdbc/execute-one!
        datasource
        ["UPDATE websub.subscription SET attempts = 0, last_error = NULL
           WHERE subscription_id = ?" subscription-id])
       {:callback callback :delivered true}
       (catch Throwable t
         (let [detail (str (name (or (:reason (ex-data t)) :delivery-failed))
                           ": " (ex-message t))
               row    (jdbc/execute-one!
                       datasource
                       ["UPDATE websub.subscription
                            SET attempts = attempts + 1, last_error = ?
                          WHERE subscription_id = ? RETURNING attempts"
                        detail subscription-id]
                       opts)]
           (when (<= 3 (:attempts row))
             (jdbc/execute-one!
              datasource
              ["DELETE FROM websub.subscription WHERE subscription_id = ?"
               subscription-id]))
           {:callback callback :delivered false :because detail
            :dropped (<= 3 (:attempts row))}))))
   (live hub topic)))

(defn hub
  [{:keys [datasource hub-url new-id clock]
    :or   {new-id random-uuid clock #(Instant/now)}}]
  {:datasource datasource
   :hub-url    hub-url
   :new-id     new-id
   :clock      clock
   :client     (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 3)) (.build))
   :policy     (resilience/policy
                {:name          :websub/subscriber
                 :retry-reasons #{:subscriber-unreachable}
                 :max-retries   2
                 :backoff-ms    [20 100 2.0]
                 :breaker       (resilience/breaker
                                 {:failure-threshold-ratio [12 20]
                                  :success-threshold       2
                                  :delay-ms                200})})})
