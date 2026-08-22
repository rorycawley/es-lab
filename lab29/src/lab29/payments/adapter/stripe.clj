(ns lab29.payments.adapter.stripe
  "The Stripe adapter, and the anticorruption layer inside it.

  Two halves, deliberately separated:

  `translate-*` are pure functions from Stripe's vocabulary into ours. They are
  the anticorruption layer, and they are the part worth testing hardest,
  because a mistranslation is silent -- it produces a plausible domain value
  that is simply wrong. `acl_test.clj` drives them with values alone.

  The rest is transport, and it is the part that can only be tested against
  something listening on a socket.

  Nothing else in the repository may name this namespace's vocabulary."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [lab29.payments.port :as port]
            [lab29.platform.resilience :as resilience])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpRequest$Builder HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.time Duration)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

;; ---------------------------------------------------------------------------
;; The anticorruption layer
;; ---------------------------------------------------------------------------

(def ^:private status->outcome
  "Stripe's PaymentIntent statuses, as far as this integration understands them.

  Everything Stripe can say is either mapped or unknown. There is no default
  branch, because the cost of guessing is that a payment we do not understand
  becomes a payment we believe succeeded."
  {"succeeded"                 :authorized
   "processing"                :pending
   "requires_action"           :pending
   "requires_confirmation"     :pending
   "requires_capture"          :pending
   "requires_payment_method"   :declined
   "canceled"                  :declined})

(defn translate-intent
  "A Stripe PaymentIntent, as our domain sees it.

  Note what does not come out: the amount, the currency and the payment id. We
  sent those, so reading them back from the provider would be trusting their
  echo of our own data. What we did not know before the call is the outcome and
  the reference, and that is exactly what this returns."
  [intent]
  (let [status (get intent "status")]
    (if-let [outcome (status->outcome status)]
      (cond-> {:outcome outcome :reference (get intent "id")}
        (= :declined outcome)
        (assoc :because (or (get-in intent ["last_payment_error" "code"])
                            (if (= "canceled" status) "canceled" "no_payment_method"))))
      (throw (ex-info "Unknown Stripe payment intent status"
                      {:reason :untranslatable-provider-response
                       :provider :stripe
                       :status status})))))

(def ^:private event-type->kind
  "The provider events this integration is subscribed to.

  Stripe sends dozens of types and retries anything that is not 2xx, so an
  unrecognised type is not an error -- it is a type we did not ask for and must
  acknowledge politely. That is a different judgement from an unrecognised
  *status* inside a type we did ask for, which is a genuine failure to
  understand and must not be swallowed."
  {"payment_intent.succeeded"        :payment/settled
   "payment_intent.payment_failed"   :payment/declined
   "payment_intent.canceled"         :payment/declined})

(defn translate-event
  "A Stripe webhook event, as our domain sees it, or `:ignored`."
  [event]
  (let [event-id   (get event "id")
        event-type (get event "type")
        intent     (get-in event ["data" "object"])]
    (when (str/blank? event-id)
      (throw (ex-info "Stripe event has no id"
                      {:reason :untranslatable-provider-event :provider :stripe})))
    (if-let [kind (event-type->kind event-type)]
      (if-let [reference (get intent "id")]
        (cond-> {:provider          "stripe"
                 :provider-event-id event-id
                 :event-type        event-type
                 :kind              kind
                 :reference         reference}
          (get-in intent ["last_payment_error" "code"])
          (assoc :because (get-in intent ["last_payment_error" "code"])))
        (throw (ex-info "Stripe event is a type we subscribe to but has no object id"
                        {:reason :untranslatable-provider-event
                         :provider :stripe
                         :event-type event-type})))
      {:provider          "stripe"
       :provider-event-id event-id
       :event-type        event-type
       :kind              :ignored})))

;; ---------------------------------------------------------------------------
;; Signature verification
;; ---------------------------------------------------------------------------

(defn- hmac-sha256 [^String secret ^String message]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes secret StandardCharsets/UTF_8) "HmacSHA256"))
    (->> (.doFinal mac (.getBytes message StandardCharsets/UTF_8))
         (map #(format "%02x" %))
         (apply str))))

(defn sign
  "Build a `Stripe-Signature` header value. Used by the fake provider and by
  tests -- the application only ever verifies."
  [secret timestamp body]
  (str "t=" timestamp ",v1=" (hmac-sha256 secret (str timestamp "." body))))

(defn verify-signature
  "Is this body really from the provider, and recent?

  Two failure modes, both real. An unsigned or wrongly signed body is somebody
  posting to a public endpoint. A correctly signed but old one is somebody
  replaying a capture of a real callback, which the signature alone cannot
  distinguish from the original -- hence the tolerance window.

  The comparison is `MessageDigest/isEqual` rather than `=` because a
  short-circuiting comparison leaks, one byte at a time, how much of a guess
  was right."
  [secret header body now-epoch-seconds tolerance-seconds]
  (let [parts (into {} (keep (fn [pair]
                               (let [[k v] (str/split pair #"=" 2)]
                                 (when v [k v])))
                             (str/split (or header "") #",")))
        timestamp (some-> (get parts "t") parse-long)
        provided  (get parts "v1")]
    (cond
      (or (nil? timestamp) (str/blank? provided))
      {:valid? false :because :malformed-signature}

      (> (abs (- now-epoch-seconds timestamp)) tolerance-seconds)
      {:valid? false :because :signature-too-old}

      (MessageDigest/isEqual (.getBytes ^String (hmac-sha256 secret (str timestamp "." body))
                                        StandardCharsets/UTF_8)
                             (.getBytes ^String provided StandardCharsets/UTF_8))
      {:valid? true}

      :else
      {:valid? false :because :signature-mismatch})))

;; ---------------------------------------------------------------------------
;; Transport
;; ---------------------------------------------------------------------------

(defn- form-encode [params]
  (str/join "&" (for [[k v] params]
                  (str (java.net.URLEncoder/encode (name k) StandardCharsets/UTF_8)
                       "="
                       (java.net.URLEncoder/encode (str v) StandardCharsets/UTF_8)))))

(defn- post-form
  "One HTTP round trip, with every transport failure named.

  A refused connection, a DNS failure and a read timeout are all the same
  thing to the caller -- we asked and do not know the answer -- and giving that
  a reason is what lets a retry policy decide whether asking again is allowed."
  [^HttpClient client base-url path headers params]
  (let [request (-> (HttpRequest/newBuilder (URI/create (str base-url path)))
                    (.timeout (Duration/ofSeconds 10))
                    (.header "Content-Type" "application/x-www-form-urlencoded")
                    (as-> b (reduce-kv (fn [^HttpRequest$Builder acc k v]
                                         (.header acc k v))
                                       b headers))
                    (.POST (HttpRequest$BodyPublishers/ofString (form-encode params)))
                    (.build))
        response (try
                   (.send client request (HttpResponse$BodyHandlers/ofString))
                   (catch java.io.IOException e
                     (throw (ex-info "Could not reach Stripe"
                                     {:reason :provider-unreachable :provider :stripe}
                                     e)))
                   (catch InterruptedException e
                     (throw (ex-info "Interrupted while calling Stripe"
                                     {:reason :provider-unreachable :provider :stripe}
                                     e))))]
    {:status (.statusCode response)
     :body   (.body response)}))

(def retry-reasons
  "Everything, and that is a claim about Stripe rather than about optimism.

  Every failure below leaves us not knowing whether the money moved, and
  normally that would forbid a retry. It is allowed here for exactly one
  reason: the request carries an idempotency key we chose before the first
  attempt, so asking again cannot charge again. Take the key away and this set
  must shrink to empty."
  #{:provider-unreachable :provider-unavailable})

(defn- attempt!
  "One complete ask-and-interpret, so that both halves are inside the retry.

  This is the shape a retry policy needs and the shape it is easy to get
  wrong: putting only the HTTP call inside the policy retries a refused
  socket and not a 503, which is the failure a provider is far more likely to
  give you during an incident."
  [^HttpClient client base-url api-key
   {:keys [payment-id amount-cents currency instrument description]}]
  (let [{:keys [status body]}
        (post-form client base-url "/v1/payment_intents"
                   {"Authorization" (str "Bearer " api-key)
                    ;; The whole reliability story in one header. Our payment
                    ;; id was chosen and written down before this call, so a
                    ;; retry after a crash -- or after the three retries above
                    ;; -- carries the same key and Stripe returns the original
                    ;; intent instead of taking the money again.
                    "Idempotency-Key" (str payment-id)}
                   {:amount amount-cents
                    :currency currency
                    :payment_method instrument
                    :confirm "true"
                    :description description})]
    (cond
      (= 200 status)
      (translate-intent (json/read-str body))

      ;; Stripe reports a declined card as 402 with an error body. That is an
      ;; answer about the money, not a failure of the integration, and it must
      ;; not be retried: the card will be just as declined in fifty
      ;; milliseconds.
      (= 402 status)
      (let [error (get (json/read-str body) "error")]
        {:outcome :declined
         :because (or (get error "code") "card_declined")
         :reference (get-in error ["payment_intent" "id"])})

      :else
      (throw (ex-info "Stripe did not answer"
                      {:reason :provider-unavailable
                       :provider :stripe
                       :status status
                       :body (subs body 0 (min 200 (count body)))})))))

(defrecord StripeGateway [client base-url api-key policy]
  port/PaymentGateway
  (provider-name [_] "stripe")

  (authorize! [_ charge]
    (resilience/call! policy #(attempt! client base-url api-key charge))))

(defn gateway
  "Build a Stripe adapter. `base-url` is a parameter so the fake provider can
  stand where the real one would.

  The breaker is built here, once, because it is per-provider state: it exists
  to notice that *this* provider is failing, which no single call can see."
  [{:keys [base-url api-key retry]}]
  (->StripeGateway (-> (HttpClient/newBuilder)
                       (.connectTimeout (Duration/ofSeconds 5))
                       (.build))
                   base-url
                   api-key
                   (resilience/policy
                    (merge {:name          :payments/stripe
                            :retry-reasons retry-reasons
                            :breaker       (resilience/breaker
                                            ;; Wider than one call's retries
                                            ;; on purpose. Four attempts that
                                            ;; all fail is one bad call; a
                                            ;; breaker is for a bad provider,
                                            ;; and tripping it on a single
                                            ;; request would make a blip into
                                            ;; an outage.
                                            {:failure-threshold-ratio [8 12]
                                             :success-threshold       2
                                             :delay-ms                200})}
                           retry))))
