(ns lab29.http
  "The driving adapter for inbound provider callbacks.

  Lab 23's rule holds: reitit, ring and jetty appear here and in `system.clj`
  and nowhere else, and `architecture_test.clj` fails the build if they spread.

  The order of the four steps below is the whole design, and each one exists
  because getting it wrong has a specific consequence:

      1. read the raw body      a signature is over bytes, not over a map
      2. verify the signature   before parsing, before believing anything
      3. translate              the adapter's ACL, provider words stop here
      4. hand to the module     domain shape only

  ## What status code to answer

  Providers retry on any non-2xx, forever, with backoff. That turns the status
  code into a control signal rather than a report:

      200  understood, or already seen, or not subscribed to
           -- anything a retry cannot improve
      400  the signature did not verify
           -- a retry cannot improve it either, and 200 would tell an attacker
              nothing distinguishes a forged callback from a real one
      500  we could not process something we did understand
           -- the one case where please-send-it-again is the right answer

  The temptation is 500 on a duplicate, because a duplicate feels like an
  error. It is not: it is the delivery guarantee working."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [lab29.payments.adapter.stripe :as stripe]
            [lab29.payments.api :as payments]
            [lab29.websub.hub :as hub]
            [lab29.websub.topics :as topics]
            [reitit.ring :as ring])
  (:import (java.io InputStream)
           (java.nio.charset StandardCharsets)))

(defn- read-body ^String [request]
  (if-let [body (:body request)]
    (if (instance? InputStream body)
      (String. (.readAllBytes ^InputStream body) StandardCharsets/UTF_8)
      (str body))
    ""))

(defn- json-response [status body]
  {:status status
   :headers {"content-type" "application/json"}
   :body (json/write-str body)})

(defn stripe-webhook-handler
  "One endpoint, built from a verifier, a translator and a module."
  [{:keys [payments signing-secret now tolerance-seconds]
    :or   {tolerance-seconds 300}}]
  (fn [request]
    (let [body      (read-body request)
          signature (get-in request [:headers "stripe-signature"])
          verified  (stripe/verify-signature signing-secret signature body
                                             (now) tolerance-seconds)]
      (if-not (:valid? verified)
        (json-response 400 {:error (name (:because verified))})
        ;; A body we cannot translate is the one case worth a 500.
        ;;
        ;; It means the provider sent a type we *did* subscribe to, in a shape
        ;; we do not understand -- a format change, most likely. Answering 2xx
        ;; discards it silently. Answering 500 makes the provider hold it and
        ;; keep offering it for the next few days, which is exactly long enough
        ;; to notice and ship a fix. That is the difference between a retry
        ;; being useless and a retry being the whole point.
        (try
          (let [event  (stripe/translate-event (json/read-str body))
                result (payments/callback! payments event)]
            (json-response 200 (cond
                                 (:accepted result)        {:received true :applied true}
                                 (:duplicate result)       {:received true :duplicate true}
                                 (:ignored result)         {:received true :ignored true}
                                 (:already-applied result) {:received true :already-applied true}
                                 (:unmatched result)       {:received true :unmatched true}
                                 :else                     {:received true})))
          (catch clojure.lang.ExceptionInfo e
            (if (= :untranslatable-provider-event (:reason (ex-data e)))
              (json-response 500 {:error "untranslatable" :retry true})
              (throw e))))))))

(defn- form-decode [body]
  (into {} (for [pair (str/split (or body "") #"&")
                 :let [[k v] (str/split pair #"=" 2)]
                 :when v]
             [(java.net.URLDecoder/decode k "UTF-8")
              (java.net.URLDecoder/decode v "UTF-8")])))

(defn topic-handler
  "The topic itself: a public resource, with discovery in its headers.

  `Link: rel=\"hub\"` is how a subscriber finds out where to subscribe without
  being told out of band. That is the whole of WebSub discovery, and it is two
  headers -- which is a fair summary of why the protocol is worth using
  instead of inventing a webhook registration API."
  [{:keys [websub hub-url]}]
  (fn [request]
    (let [product-id (parse-uuid (get-in request [:path-params :product-id] ""))
          topic      (topics/topic-url (:base-url websub) product-id)]
      (if-let [body (some->> product-id (topics/representation (:datasource websub)))]
        {:status 200
         :headers {"content-type" "application/json"
                   "Link" (str "<" topic ">; rel=\"self\", <" hub-url ">; rel=\"hub\"")}
         :body (topics/body body)}
        (json-response 404 {:error "not-found"})))))

(defn hub-handler
  "One endpoint, two modes, and a verification round trip in the middle.

  WebSub says a hub SHOULD answer 202 and verify asynchronously. This one
  verifies inline and answers the outcome, so that a test can assert what
  happened without racing a background thread. The difference is scheduling,
  not semantics -- and it is called out in the README rather than left for a
  reader to discover."
  [{:keys [websub-hub]}]
  (fn [request]
    (let [params   (form-decode (read-body request))
          mode     (get params "hub.mode")
          topic    (get params "hub.topic")
          callback (get params "hub.callback")]
      (cond
        (or (str/blank? topic) (str/blank? callback))
        (json-response 400 {:error "hub.topic and hub.callback are required"})

        (= "subscribe" mode)
        (let [result (hub/subscribe! websub-hub
                                     {:topic topic
                                      :callback callback
                                      :secret (get params "hub.secret")
                                      :lease-seconds (some-> (get params "hub.lease_seconds")
                                                             parse-long)})]
          (if (:accepted result)
            (json-response 202 {:accepted true :lease-seconds
                                (get-in result [:accepted :lease-seconds])})
            (json-response 400 {:error (name (:rejected result))})))

        (= "unsubscribe" mode)
        (let [result (hub/unsubscribe! websub-hub {:topic topic :callback callback})]
          (if (:accepted result)
            (json-response 202 {:accepted true})
            (json-response 400 {:error (name (:rejected result))})))

        :else
        (json-response 400 {:error "hub.mode must be subscribe or unsubscribe"})))))

(defn router
  [{:keys [payments] :as options}]
  (ring/ring-handler
   (ring/router
    [["/health" {:get (fn [_] (json-response 200 {:status "ok"
                                                  :gateway (payments/provider payments)}))}]
     ["/webhooks/stripe" {:post (stripe-webhook-handler options)}]
     ["/hub" {:post (hub-handler options)}]
     ["/v1/products/:product-id" {:get (topic-handler options)}]])
   (ring/create-default-handler
    {:not-found (fn [_] (json-response 404 {:error "not-found"}))})))
