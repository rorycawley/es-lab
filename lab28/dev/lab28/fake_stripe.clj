(ns lab28.fake-stripe
  "A fake Stripe, on a real socket.

  It lives in `dev/` for the reason lab 24 gave about its identity provider: a
  provider is a dependency of your tests and never of your application.

  It is deliberately *not* generous. It enforces the `Idempotency-Key` header
  rather than ignoring it, rejects a missing API key, uses Stripe's real test
  payment methods, and returns Stripe's real status vocabulary. A fake that is
  easier than the thing it fakes is a way of not finding out."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [lab28.payments.adapter.stripe :as stripe]
            [ring.adapter.jetty :as jetty])
  (:import (java.io InputStream)
           (java.nio.charset StandardCharsets)))

(def signing-secret "whsec_lab28_test_secret")

(defn- form-decode [body]
  (into {} (for [pair (str/split (or body "") #"&")
                 :let [[k v] (str/split pair #"=" 2)]
                 :when v]
             [(java.net.URLDecoder/decode k StandardCharsets/UTF_8)
              (java.net.URLDecoder/decode v StandardCharsets/UTF_8)])))

(defn- read-body [request]
  (if-let [body (:body request)]
    (String. (.readAllBytes ^InputStream body) StandardCharsets/UTF_8)
    ""))

(defn- json-response [status body]
  {:status status
   :headers {"content-type" "application/json"}
   :body (json/write-str body)})

(defn- intent-for [params id]
  (let [method (get params "payment_method")]
    (case method
      "pm_card_chargeDeclined"
      {"id" id
       "object" "payment_intent"
       "status" "requires_payment_method"
       "amount" (parse-long (get params "amount" "0"))
       "currency" (get params "currency")
       "last_payment_error" {"code" "card_declined"
                             "message" "Your card was declined."}}

      "pm_card_authenticationRequired"
      {"id" id "object" "payment_intent" "status" "requires_action"
       "amount" (parse-long (get params "amount" "0"))
       "currency" (get params "currency")}

      {"id" id
       "object" "payment_intent"
       "status" "succeeded"
       "amount" (parse-long (get params "amount" "0"))
       "currency" (get params "currency")})))

(defn handler
  "`state` is an atom holding `{:by-key {} :calls []}`."
  [state]
  (fn [request]
    (let [body    (read-body request)
          params  (form-decode body)
          headers (:headers request)
          key     (get headers "idempotency-key")]
      (swap! state update :calls conj {:path (:uri request) :params params
                                       :idempotency-key key})
      (cond
        ;; A provider having a bad few minutes. Counted down rather than
        ;; toggled, so a test can distinguish "retry absorbed it" from
        ;; "the breaker gave up".
        (pos? (:failures-left @state 0))
        (do (swap! state update :failures-left dec)
            (json-response 503 {"error" {"message" "service unavailable"}}))

        (not (str/starts-with? (str (get headers "authorization")) "Bearer sk_"))
        (json-response 401 {"error" {"type" "invalid_request_error"
                                     "message" "Invalid API Key provided"}})

        (not= "/v1/payment_intents" (:uri request))
        (json-response 404 {"error" {"message" "Unknown endpoint"}})

        (str/blank? key)
        ;; The real Stripe does not require this header. This fake does,
        ;; because a lab about idempotency should fail loudly if the adapter
        ;; ever stops sending it -- and `gateway_contract_test.clj` would
        ;; otherwise pass while the deployed system double-charged.
        (json-response 400 {"error" {"type" "invalid_request_error"
                                     "message" "Idempotency-Key required by this fake"}})

        :else
        (if-let [existing (get-in @state [:by-key key])]
          ;; The whole reason the header exists: the same key replays the
          ;; original answer instead of moving money again.
          (json-response 200 (assoc existing "lab28_replayed" true))
          (let [id     (str "pi_" (str/replace (str (random-uuid)) "-" ""))
                intent (intent-for params id)]
            (swap! state assoc-in [:by-key key] intent)
            (if (= "requires_payment_method" (get intent "status"))
              ;; Stripe reports a declined card as 402 with the intent inside
              ;; the error, not as a 200 with a sad status.
              (json-response 402 {"error" {"type" "card_error"
                                           "code" "card_declined"
                                           "message" "Your card was declined."
                                           "payment_intent" intent}})
              (json-response 200 intent))))))))

(defn start!
  "Start on an ephemeral port. Returns `{:server :port :base-url :state}`."
  []
  (let [state  (atom {:by-key {} :calls [] :failures-left 0})
        server (jetty/run-jetty (handler state) {:port 0 :join? false})
        port   (.getLocalPort (first (.getConnectors server)))]
    {:server server :port port :base-url (str "http://localhost:" port) :state state}))

(defn stop! [{:keys [server]}] (.stop server))

(defn fail-times!
  "Make the next `n` requests answer 503 without touching any payment."
  [{:keys [state]} n]
  (swap! state assoc :failures-left n))

(defn charges [{:keys [state]}]
  (filterv #(= "/v1/payment_intents" (:path %)) (:calls @state)))

(defn intents [{:keys [state]}] (vals (:by-key @state)))

;; ---------------------------------------------------------------------------
;; Callbacks
;; ---------------------------------------------------------------------------

(defn event
  "A Stripe webhook event body, in Stripe's shape."
  ([event-type intent] (event event-type intent (str "evt_" (random-uuid))))
  ([event-type intent event-id]
   {"id" event-id
    "object" "event"
    "type" event-type
    "created" (quot (System/currentTimeMillis) 1000)
    "data" {"object" intent}}))

(defn signed-request
  "A ring request carrying a correctly signed Stripe callback.

  Tests build these rather than waiting for an asynchronous delivery, which
  keeps them deterministic while exercising exactly the bytes and header the
  real provider would send."
  ([event-body] (signed-request event-body (quot (System/currentTimeMillis) 1000)))
  ([event-body timestamp]
   (let [body (json/write-str event-body)]
     {:request-method :post
      :uri "/webhooks/stripe"
      :headers {"stripe-signature" (stripe/sign signing-secret timestamp body)
                "content-type" "application/json"}
      :body body})))
