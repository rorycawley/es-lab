(ns lab28.idempotency-test
  "What happens when the same thing happens twice.

  Every integration in this lab is at-least-once in at least one direction, so
  \"twice\" is not an edge case to guard against -- it is the normal operating
  condition, and the suite is arranged by *where* the duplication comes from:

      the message      a redelivered integration event
      the crash        a remote effect whose answer we never received
      the race         two workers picking up the same message at once
      the provider     a callback delivered again, and again

  The last section is the one that matters most, because it is the one where
  the two integrations give different answers."
  (:require [clojure.test :refer [deftest is testing]]
            [lab28.catalog.api :as catalog]
            [lab28.chaos :as chaos]
            [lab28.fake-sendgrid :as fake-sendgrid]
            [lab28.fake-stripe :as fake-stripe]
            [lab28.fixture :as fixture]
            [lab28.notifications.adapter.sendgrid :as sendgrid]
            [lab28.notifications.api :as notifications]
            [lab28.ordering.api :as ordering]
            [lab28.payments.adapter.stripe :as stripe]
            [lab28.payments.api :as payments]
            [lab28.postgres :as postgres]
            [lab28.recorder :as recorder]
            [lab28.system :as system]))

(def vanilla #uuid "0f1c2b3a-0000-4000-8000-000000000026")

(defn- stocked-order!
  "An order, and the delivery Payments would receive for it.

  These tests run with `:subscribe? false`, so the relay publishes to an empty
  bus and hands the delivery back instead. That is not a test trick: it is the
  state a deployment is in whenever a consumer is down, and it is the only way
  to drive the consumer by hand and control exactly how often."
  [{:keys [catalog ordering] :as app} order-id]
  (catalog/change-price! catalog {:command-id (random-uuid)
                                  :correlation-id (random-uuid)
                                  :product-id vanilla
                                  :product-name "vanilla"
                                  :price-cents 300})
  (doseq [delivery (:published (system/relay-catalog! app))]
    (ordering/receive! ordering (select-keys delivery [:headers :message])))
  (ordering/place-order! ordering {:order-id order-id
                                   :correlation-id (random-uuid)
                                   :product-id vanilla
                                   :quantity 2
                                   :customer-email "ada@example.test"
                                   :payment-method "pm_card_visa"})
  (let [[delivery] (:published (system/relay-ordering! app))]
    (select-keys delivery [:headers :message])))

;; ---------------------------------------------------------------------------
;; Duplication from the message
;; ---------------------------------------------------------------------------

(deftest a-redelivered-order-charges-once-test
  (fixture/with-providers {:subscribe? false}
    (fn [{:keys [app stripe]}]
      (let [order-id (random-uuid)
            delivery (stocked-order! app order-id)]
        (is (:accepted (payments/charge! (:payments app) delivery)))
        (testing "the same delivery, four more times"
          (doseq [_ (range 4)]
            (is (:duplicate (payments/charge! (:payments app) delivery)))))
        (is (= 1 (count (fake-stripe/charges stripe)))
            "one request reached the provider at all")))))

(deftest the-same-fact-in-a-new-envelope-charges-once-test
  ;; Lab 25's warning: an inbox keyed by message id would miss this. Ours is
  ;; keyed by the fact, and the payment row's state is the second guard.
  (fixture/with-providers {:subscribe? false}
    (fn [{:keys [app stripe]}]
      (let [order-id (random-uuid)
            delivery (stocked-order! app order-id)]
        (payments/charge! (:payments app) delivery)
        (is (:duplicate (payments/charge!
                         (:payments app)
                         (assoc-in delivery [:message :message/id] (random-uuid)))))
        (is (= 1 (count (fake-stripe/charges stripe))))))))

;; ---------------------------------------------------------------------------
;; Duplication from a crash
;; ---------------------------------------------------------------------------

(deftest a-crash-after-the-money-moved-does-not-move-it-again-test
  ;; The scenario the whole slice is shaped around. The provider charged, this
  ;; process died before it could write that down, and the retry must converge
  ;; rather than charge again.
  (recorder/start!)
  (postgres/truncate!)
  (let [provider (fake-stripe/start!)]
    (try
      (let [healthy  (stripe/gateway {:base-url (:base-url provider)
                                      :api-key "sk_test_lab28"})
            app      (system/start (assoc (postgres/config)
                                          :gateway {:provider :given
                                                    :instance (chaos/crash-after-authorize healthy)}
                                          :emailer {:provider :memory})
                                   {:subscribe? false})
            order-id (random-uuid)
            delivery (stocked-order! app order-id)]

        (testing "the first attempt dies with the charge already made"
          (is (thrown? clojure.lang.ExceptionInfo
                       (payments/charge! (:payments app) delivery)))
          (is (= 1 (count (fake-stripe/charges provider))))
          (is (= "requested" (:status (:found (payments/get-payment
                                               (:payments app) {:order-id order-id}))))
              "the local record still says we do not know"))

        (testing "the retry asks again, with the same key, and converges"
          (is (:accepted (payments/charge! (:payments app) delivery)))
          (is (= 2 (count (fake-stripe/charges provider)))
              "two requests were sent")
          (is (= 1 (count (fake-stripe/intents provider)))
              "and exactly one payment exists at the provider")
          (is (apply = (map :idempotency-key (fake-stripe/charges provider)))
              "because both carried the payment id written down before the first")
          (let [payment (:found (payments/get-payment (:payments app) {:order-id order-id}))]
            (is (= "authorized" (:status payment)))
            (is (some? (:gateway-reference payment))))))
      (finally (fake-stripe/stop! provider)))))

(deftest a-provider-we-could-not-reach-leaves-the-payment-visible-test
  ;; Not a crash and not a decline: the provider was simply not there. The
  ;; payment must stay `requested` -- an honest \"we do not know\" -- rather than
  ;; being written off as declined, and it must still be findable, because a
  ;; row nobody sweeps is the hole this lab leaves open on purpose.
  (recorder/start!)
  (postgres/truncate!)
  (let [provider (fake-stripe/start!)
        unreachable (stripe/gateway {:base-url "http://localhost:1"
                                     :api-key "sk_test_lab28"})
        app (system/start (assoc (postgres/config)
                                 :gateway {:provider :given :instance unreachable}
                                 :emailer {:provider :memory})
                          {:subscribe? false})]
    (try
      (let [order-id (random-uuid)
            delivery (stocked-order! app order-id)]
        (is (thrown? Exception (payments/charge! (:payments app) delivery)))
        (let [payment (:found (payments/get-payment (:payments app) {:order-id order-id}))]
          (is (= "requested" (:status payment))
              "we asked and never heard back, and the record says exactly that")
          (is (nil? (:gateway-reference payment)))
          (is (nil? (:decline-reason payment))
              "a provider being down is not the customer's card being refused"))
        (is (empty? (:published (system/relay-payments! app)))
            "and nothing was announced on the strength of a request that failed")

        (testing "a retry once the provider is back converges on one charge"
          (let [healthy (stripe/gateway {:base-url (:base-url provider)
                                         :api-key "sk_test_lab28"})
                back    (system/start (assoc (postgres/config)
                                             :gateway {:provider :given :instance healthy}
                                             :emailer {:provider :memory})
                                      {:subscribe? false})]
            (is (:accepted (payments/charge! (:payments back) delivery)))
            (is (= 1 (count (fake-stripe/intents provider))))
            (is (= "authorized" (:status (:found (payments/get-payment
                                                  (:payments back) {:order-id order-id}))))))))
      (finally (fake-stripe/stop! provider)))))

(deftest a-rate-limited-emailer-leaves-the-receipt-queued-test
  ;; The same shape on the notifications side, and the same rule: a provider
  ;; refusing to answer is not a recipient refusing to receive.
  (recorder/start!)
  (postgres/truncate!)
  (let [provider (fake-sendgrid/start!)
        app (system/start (assoc (postgres/config)
                                 :gateway {:provider :memory}
                                 :emailer {:provider :sendgrid
                                           :base-url (:base-url provider)
                                           :api-key "SG.lab28"})
                          {:subscribe? false})]
    (try
      (let [order-id (random-uuid)
            delivery (stocked-order! app order-id)]
        (payments/charge! (:payments app) delivery)
        (let [[settled] (:published (system/relay-payments! app))
              fact-id   (get-in settled [:message :payload :fact-id])
              receipt   (select-keys settled [:headers :message])]
          (fake-sendgrid/fail-times! provider 99 429)
          (is (thrown? Exception (notifications/receive! (:notifications app) receipt)))
          (let [row (:found (notifications/get-notification
                             (:notifications app) {:fact-id fact-id}))]
            (is (= "queued" (:status row)))
            (is (= 0 (:attempts row))
                "an attempt that never got an answer is not an attempt that failed"))

          (testing "and a delivery attempt once the limit lifts sends it"
            (fake-sendgrid/fail-times! provider 0 nil)
            (is (:accepted (notifications/receive! (:notifications app) receipt)))
            (is (= 1 (count (fake-sendgrid/sent provider))))
            (is (= "sent" (:status (:found (notifications/get-notification
                                            (:notifications app) {:fact-id fact-id}))))))))
      (finally (fake-sendgrid/stop! provider)))))

(deftest a-crash-after-the-email-sent-sends-it-again-test
  ;; The mirror image, and the honest one. Same structure, same care, and the
  ;; customer still gets two receipts -- because SendGrid has no idempotency
  ;; key for the retry to carry.
  (recorder/start!)
  (postgres/truncate!)
  (let [provider (fake-sendgrid/start!)]
    (try
      (let [healthy (sendgrid/emailer {:base-url (:base-url provider) :api-key "SG.lab28"})
            app     (system/start (assoc (postgres/config)
                                         :gateway {:provider :memory}
                                         :emailer {:provider :given
                                                   :instance (chaos/crash-after-send healthy)})
                                  {:subscribe? false})
            order-id (random-uuid)
            delivery (stocked-order! app order-id)]
        (payments/charge! (:payments app) delivery)
        (let [[settled] (:published (system/relay-payments! app))
              fact-id   (get-in settled [:message :payload :fact-id])
              receipt   (select-keys settled [:headers :message])]
          (is (thrown? clojure.lang.ExceptionInfo
                       (notifications/receive! (:notifications app) receipt)))
          (is (= 1 (count (fake-sendgrid/sent provider))))

          (is (:accepted (notifications/receive! (:notifications app) receipt)))
          (is (= 2 (count (fake-sendgrid/sent provider)))
              "two receipts. This is what at-least-once costs, and no amount of
               local bookkeeping avoids it")

          (testing "what the ledger can do is stop the third and record the truth"
            (is (:duplicate (notifications/receive! (:notifications app) receipt)))
            (is (= 2 (count (fake-sendgrid/sent provider))))
            (let [row (:found (notifications/get-notification
                               (:notifications app) {:fact-id fact-id}))]
              (is (= "sent" (:status row)))
              (is (= 1 (:attempts row))
                  "the crashed attempt never got as far as recording itself,
                   which is exactly why the duplicate happened")))))
      (finally (fake-sendgrid/stop! provider)))))

;; ---------------------------------------------------------------------------
;; Duplication from a race
;; ---------------------------------------------------------------------------

(deftest concurrent-deliveries-of-one-order-charge-once-test
  ;; Two relays, two workers, one message picked up twice at the same instant.
  ;; Both threads reach the gateway; what saves the customer is that the
  ;; database, not the process, chose the payment id they both present.
  (fixture/with-providers {:subscribe? false}
    (fn [{:keys [app stripe]}]
      (let [order-id (random-uuid)
            delivery (stocked-order! app order-id)
            gate     (promise)
            attempts (reduce (fn [started _]
                               (conj started
                                     (future
                                       @gate
                                       (try (payments/charge! (:payments app) delivery)
                                            (catch Exception e {:error e})))))
                             []
                             (range 8))]
        (deliver gate true)
        (let [results (mapv deref attempts)]
          (is (empty? (filter :error results)))
          (is (= 1 (count (fake-stripe/intents stripe)))
              "eight attempts, one payment intent")
          (is (= 1 (count (set (map :idempotency-key (fake-stripe/charges stripe)))))
              "because every attempt presented the same key")
          (is (= "authorized" (:status (:found (payments/get-payment
                                                (:payments app) {:order-id order-id})))))
          (is (= 1 (count (:published (system/relay-payments! app))))
              "and one announcement, not eight"))))))

;; ---------------------------------------------------------------------------
;; Duplication from the provider
;; ---------------------------------------------------------------------------

(deftest what-the-provider-gives-you-bounds-what-you-can-promise-test
  ;; The lab in one assertion. Same architecture, same discipline, two
  ;; different guarantees -- and the difference is not in our code.
  (fixture/with-providers {:subscribe? false}
    (fn [{:keys [app stripe sendgrid]}]
      (let [order-id (random-uuid)
            delivery (stocked-order! app order-id)]
        (payments/charge! (:payments app) delivery)
        (let [[settled] (:published (system/relay-payments! app))
              receipt   (select-keys settled [:headers :message])]
          (notifications/receive! (:notifications app) receipt)

          ;; Ask each provider to do the same work a second time, directly,
          ;; with the identifier we would use on a retry.
          (let [charge-again (first (fake-stripe/charges stripe))]
            (payments/charge! (:payments app) delivery)
            (notifications/receive! (:notifications app) receipt)
            (is (some? charge-again)))

          (is (= 1 (count (fake-stripe/intents stripe)))
              "payments: an idempotency key, so twice asked is once charged")
          (is (= 1 (count (fake-sendgrid/sent sendgrid)))
              "notifications: no idempotency key, so the only defence is our
               own ledger -- which works here, and did not in the crash above"))))))
