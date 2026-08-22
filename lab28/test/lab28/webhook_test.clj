(ns lab28.webhook-test
  "The inbound edge: an endpoint anyone can post to, with delivery semantics
  chosen by somebody else.

  Almost all of this needs no socket, because a ring handler is a function from
  a map to a map -- lab 23's observation, and it is what makes an integration
  edge cheap to test properly rather than expensively to test badly. One test
  starts Jetty to prove there is a socket."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [lab28.catalog.api :as catalog]
            [lab28.fake-stripe :as fake-stripe]
            [lab28.fixture :as fixture]
            [lab28.ordering.api :as ordering]
            [lab28.payments.adapter.stripe :as stripe]
            [lab28.payments.api :as payments]
            [lab28.system :as system])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)))

(def vanilla #uuid "0f1c2b3a-0000-4000-8000-000000000026")
(def ^:private now (constantly 1700000000))

(defn- endpoint
  ([app] (endpoint app {}))
  ([app options]
   (system/handler app (merge {:signing-secret fake-stripe/signing-secret :now now}
                              options))))

(defn- post [handler request]
  (let [response (handler request)]
    (assoc response :parsed (json/read-str (:body response)))))

(defn- authorized-payment!
  "Drive a real order through to an authorized payment, and return its
  gateway reference -- the string the provider will call back about."
  [{:keys [catalog ordering payments] :as app}]
  (let [order-id (random-uuid)]
    (catalog/change-price! catalog {:command-id (random-uuid)
                                    :correlation-id (random-uuid)
                                    :product-id vanilla
                                    :product-name "vanilla"
                                    :price-cents 300})
    (system/relay-catalog! app)
    (ordering/place-order! ordering {:order-id order-id
                                     :correlation-id (random-uuid)
                                     :product-id vanilla
                                     :quantity 1
                                     :customer-email "ada@example.test"
                                     :payment-method "pm_card_visa"})
    (system/relay-ordering! app)
    {:order-id order-id
     :reference (:gateway-reference (:found (payments/get-payment
                                             payments {:order-id order-id})))}))

(defn- settled-event [reference]
  (fake-stripe/event "payment_intent.succeeded" {"id" reference}))

;; ---------------------------------------------------------------------------
;; Signature
;; ---------------------------------------------------------------------------

(deftest an-unsigned-callback-changes-nothing-test
  (fixture/with-system
    (fn [{:keys [payments] :as app}]
      (let [{:keys [order-id reference]} (authorized-payment! app)
            handler (endpoint app)
            body    (json/write-str (settled-event reference))
            response (post handler {:request-method :post
                                    :uri "/webhooks/stripe"
                                    :headers {}
                                    :body body})]
        (is (= 400 (:status response)))
        (is (= "malformed-signature" (get (:parsed response) "error")))
        (is (= "authorized" (:status (:found (payments/get-payment
                                              payments {:order-id order-id}))))
            "nothing was applied on the strength of an unsigned body")))))

(deftest a-forged-signature-changes-nothing-test
  (fixture/with-system
    (fn [{:keys [payments] :as app}]
      (let [{:keys [order-id reference]} (authorized-payment! app)
            event   (settled-event reference)
            request (fake-stripe/signed-request event (now))
            forged  (assoc-in request [:headers "stripe-signature"]
                              (stripe/sign
                               "whsec_attacker" (now) (:body request)))
            response (post (endpoint app) forged)]
        (is (= 400 (:status response)))
        (is (= "signature-mismatch" (get (:parsed response) "error")))
        (is (= "authorized" (:status (:found (payments/get-payment
                                              payments {:order-id order-id})))))))))

(deftest a-replayed-callback-from-last-week-changes-nothing-test
  ;; The signature is still perfectly valid. Only the clock says no.
  (fixture/with-system
    (fn [{:keys [payments] :as app}]
      (let [{:keys [order-id reference]} (authorized-payment! app)
            old-request (fake-stripe/signed-request (settled-event reference)
                                                    (- (now) 86400))
            response    (post (endpoint app) old-request)]
        (is (= 400 (:status response)))
        (is (= "signature-too-old" (get (:parsed response) "error")))
        (is (= "authorized" (:status (:found (payments/get-payment
                                              payments {:order-id order-id})))))))))

;; ---------------------------------------------------------------------------
;; Delivery semantics
;; ---------------------------------------------------------------------------

(deftest a-signed-callback-settles-the-payment-test
  (fixture/with-system
    (fn [{:keys [payments] :as app}]
      (let [{:keys [order-id reference]} (authorized-payment! app)
            response (post (endpoint app)
                           (fake-stripe/signed-request (settled-event reference) (now)))]
        (is (= 200 (:status response)))
        (is (true? (get (:parsed response) "applied")))
        (is (= "settled" (:status (:found (payments/get-payment
                                           payments {:order-id order-id}))))
            "the synchronous answer was optimistic; the callback is the
             confirmation")))))

(deftest a-redelivered-callback-is-a-200-and-a-no-op-test
  ;; Providers retry on any non-2xx, so answering 500 to a duplicate builds a
  ;; loop that never ends. A duplicate is the delivery guarantee working.
  (fixture/with-system
    (fn [{:keys [payments] :as app}]
      (let [{:keys [order-id reference]} (authorized-payment! app)
            event   (settled-event reference)
            handler (endpoint app)
            request (fake-stripe/signed-request event (now))]
        (is (true? (get (:parsed (post handler request)) "applied")))
        (doseq [_ (range 4)]
          (let [again (post handler request)]
            (is (= 200 (:status again)))
            (is (true? (get (:parsed again) "duplicate")))))
        (is (= "settled" (:status (:found (payments/get-payment
                                           payments {:order-id order-id})))))))))

(deftest the-same-fact-in-a-new-envelope-is-still-applied-once-test
  ;; A provider can genuinely re-send the same underlying event under a new
  ;; event id. The webhook inbox cannot catch that -- but the state guard can,
  ;; because a payment already settled is not settled again.
  (fixture/with-system
    (fn [{:keys [payments] :as app}]
      (let [{:keys [order-id reference]} (authorized-payment! app)
            handler (endpoint app)]
        (post handler (fake-stripe/signed-request (settled-event reference) (now)))
        (let [second-envelope (post handler (fake-stripe/signed-request
                                             (settled-event reference) (now)))]
          (is (= 200 (:status second-envelope)))
          (is (true? (get (:parsed second-envelope) "already-applied"))
              "understood, claimed, and about a payment already past this state
               -- which is a different thing from never having heard of it"))
        (is (= "settled" (:status (:found (payments/get-payment
                                           payments {:order-id order-id})))))))))

(deftest an-event-type-we-never-subscribed-to-is-acknowledged-test
  (fixture/with-system
    (fn [app]
      (let [event    (fake-stripe/event "customer.subscription.trial_will_end"
                                        {"id" "sub_1"})
            response (post (endpoint app) (fake-stripe/signed-request event (now)))]
        (is (= 200 (:status response)))
        (is (true? (get (:parsed response) "ignored"))
            "polite silence, not an error, and not a row in a table that would
             grow forever to answer no question")))))

(deftest a-callback-resolves-a-payment-the-provider-never-finished-test
  ;; The reason a callback endpoint exists at all.
  ;;
  ;; A card needing 3-D Secure comes back `requires_action`: neither taken nor
  ;; refused. Nothing was announced, so no receipt went out. Then the customer
  ;; completes the challenge, the provider calls back, and *that* is where this
  ;; payment first becomes a fact worth telling anyone about.
  (fixture/with-system
    (fn [{:keys [catalog ordering payments] :as app}]
      (let [order-id (random-uuid)]
        (catalog/change-price! catalog {:command-id (random-uuid)
                                        :correlation-id (random-uuid)
                                        :product-id vanilla
                                        :product-name "vanilla"
                                        :price-cents 300})
        (system/relay-catalog! app)
        (ordering/place-order! ordering {:order-id order-id
                                         :correlation-id (random-uuid)
                                         :product-id vanilla
                                         :quantity 1
                                         :customer-email "ada@example.test"
                                         :payment-method "pm_card_authenticationRequired"})
        (system/relay-ordering! app)

        (testing "held, and deliberately quiet about it"
          (is (= "pending" (:status (:found (payments/get-payment
                                             payments {:order-id order-id})))))
          (is (empty? (:published (system/relay-payments! app)))
              "nothing is announced for money that has not moved"))

        (testing "the callback both settles it and announces it"
          (let [reference (:gateway-reference (:found (payments/get-payment
                                                       payments {:order-id order-id})))
                response  (post (endpoint app)
                                (fake-stripe/signed-request (settled-event reference) (now)))]
            (is (= 200 (:status response)))
            (is (true? (get (:parsed response) "applied")))
            (is (= "settled" (:status (:found (payments/get-payment
                                               payments {:order-id order-id})))))
            (is (= 1 (count (:published (system/relay-payments! app))))
                "the announcement came from the callback's own transaction")))))))

(deftest a-payment-is-announced-once-whichever-path-found-it-test
  ;; Both paths call the same `announce/succeeded!`, and neither checks whether
  ;; the other already did. `UNIQUE (payment_id)` is the coordination.
  (fixture/with-system
    (fn [app]
      (let [{:keys [reference]} (authorized-payment! app)]
        (is (= 1 (count (:published (system/relay-payments! app))))
            "authorization announced it")
        (post (endpoint app) (fake-stripe/signed-request (settled-event reference) (now)))
        (is (empty? (:published (system/relay-payments! app)))
            "and the callback, arriving later, did not announce it again")))))

(deftest a-body-we-cannot-translate-is-the-one-case-worth-a-500-test
  ;; The provider changed a shape we depend on. A 2xx here throws the event
  ;; away; a 500 makes them keep offering it while somebody ships a fix.
  (fixture/with-system
    (fn [app]
      (let [broken   {"id" "evt_broken" "type" "payment_intent.succeeded"
                      "data" {"object" {}}}
            response (post (endpoint app) (fake-stripe/signed-request broken (now)))]
        (is (= 500 (:status response)))
        (is (true? (get (:parsed response) "retry")))))))

(deftest a-callback-about-a-payment-we-never-made-is-not-an-error-test
  (fixture/with-system
    (fn [app]
      (let [response (post (endpoint app)
                           (fake-stripe/signed-request
                            (settled-event "pi_never_heard_of_it") (now)))]
        (is (= 200 (:status response)))
        (is (true? (get (:parsed response) "unmatched")))))))

(deftest a-callback-that-arrives-out-of-order-still-lands-test
  ;; The callback can beat our own recording of the synchronous answer. It
  ;; must not be lost, and it must not require the payment to be `authorized`
  ;; first -- `requested` is a legitimate state to settle from.
  (fixture/with-system
    (fn [{:keys [payments] :as app}]
      (let [{:keys [order-id reference]} (authorized-payment! app)]
        (post (endpoint app) (fake-stripe/signed-request (settled-event reference) (now)))
        (is (= "settled" (:status (:found (payments/get-payment
                                           payments {:order-id order-id})))))))))

;; ---------------------------------------------------------------------------
;; One socket
;; ---------------------------------------------------------------------------

(deftest there-really-is-a-server-test
  (fixture/with-system
    (fn [app]
      (let [server (system/serve! app {:port 0
                                       :signing-secret fake-stripe/signing-secret
                                       :now now})
            port   (.getLocalPort (first (.getConnectors server)))]
        (try
          (let [client   (HttpClient/newHttpClient)
                {:keys [order-id reference]} (authorized-payment! app)
                request  (fake-stripe/signed-request (settled-event reference) (now))
                response (.send client
                                (-> (HttpRequest/newBuilder
                                     (URI/create (str "http://localhost:" port
                                                      "/webhooks/stripe")))
                                    (.header "stripe-signature"
                                             (get-in request [:headers "stripe-signature"]))
                                    (.header "content-type" "application/json")
                                    (.POST (HttpRequest$BodyPublishers/ofString
                                            (:body request)))
                                    (.build))
                                (HttpResponse$BodyHandlers/ofString))]
            (is (= 200 (.statusCode response)))
            (is (true? (get (json/read-str (.body response)) "applied")))
            (is (= "settled" (:status (:found (payments/get-payment
                                               (:payments app) {:order-id order-id}))))))
          (finally (.stop server)))))))
