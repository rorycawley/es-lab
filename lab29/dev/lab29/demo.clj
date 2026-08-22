(ns lab29.demo
  (:gen-class)
  (:require [lab29.catalog.api :as catalog]
            [lab29.chaos :as chaos]
            [lab29.fake-sendgrid :as fake-sendgrid]
            [lab29.fake-stripe :as fake-stripe]
            [lab29.notifications.api :as notifications]
            [lab29.payments.adapter.stripe :as stripe-adapter]
            [lab29.payments.api :as payments]
            [lab29.ordering.api :as ordering]
            [lab29.platform.bus :as bus]
            [lab29.platform.relay :as relay]
            [lab29.postgres :as postgres]
            [lab29.recorder :as recorder]
            [lab29.system :as system]))

(def vanilla   #uuid "0f1c2b3a-0000-4000-8000-000000000026")
(def customer "ada@example.test")
(def rule "  ──────────────────────────────────────────────────────────────")

(defn- order! [ordering product-id quantity payment-method]
  (let [order-id (random-uuid)]
    (ordering/place-order! ordering {:order-id order-id
                                     :correlation-id (random-uuid)
                                     :product-id product-id
                                     :quantity quantity
                                     :customer-email customer
                                     :payment-method payment-method})
    order-id))

(defn -main [& _]
  (recorder/start!)
  (postgres/truncate!)
  (let [stripe   (fake-stripe/start!)
        sendgrid (fake-sendgrid/start!)
        app      (system/start (assoc (postgres/config)
                                      :gateway {:provider :stripe
                                                :base-url (:base-url stripe)
                                                :api-key "sk_test_lab29"}
                                      :emailer {:provider :sendgrid
                                                :base-url (:base-url sendgrid)
                                                :api-key "SG.lab29"}))
        {:keys [catalog ordering payments notifications]} app]
    (catalog/change-price! catalog {:command-id (random-uuid)
                                    :correlation-id (random-uuid)
                                    :product-id vanilla
                                    :product-name "vanilla"
                                    :price-cents 300})
    (system/relay-catalog! app)

    (println)
    (println "  Two providers we do not want to be married to.")
    (println rule)
    (println)
    (println (format "    payments      %s" (payments/provider payments)))
    (println (format "    notifications %s" (notifications/provider notifications)))
    (println "    Chosen in system.clj. Named nowhere else in src/.")
    (println)

    (println rule)
    (println)
    (println "  One order, four modules, three contracts, no shared transaction.")
    (println)
    (let [order-id (order! ordering vanilla 2 "pm_card_visa")]
      (println (format "    %-34s %s" "ordering/order-placed"
                       (:ordering (system/relay-all! app))))
      (let [payment (:found (payments/get-payment payments {:order-id order-id}))]
        (println (format "    %-34s %s  %s" "payments -> stripe"
                         (:status payment) (:gateway-reference payment))))
      (println (format "    %-34s %s" "notifications -> sendgrid"
                       (count (fake-sendgrid/sent sendgrid))))
      (println)

      (println rule)
      (println)
      (println "  Stripe calls back. Anyone can post to that endpoint.")
      (println)
      (let [handler   (system/handler app {:signing-secret fake-stripe/signing-secret
                                           :now #(quot (System/currentTimeMillis) 1000)})
            reference (:gateway-reference (:found (payments/get-payment
                                                   payments {:order-id order-id})))
            body      (fake-stripe/event "payment_intent.succeeded" {"id" reference})
            signed    (fake-stripe/signed-request body)]
        (doseq [[label request]
                [["unsigned"        (assoc signed :headers {})]
                 ["forged"          (assoc-in signed [:headers "stripe-signature"]
                                              (stripe-adapter/sign "whsec_attacker"
                                                                   (quot (System/currentTimeMillis) 1000)
                                                                   (:body signed)))]
                 ["genuine"         signed]
                 ["genuine, again"  signed]
                 ["a type we ignore" (fake-stripe/signed-request
                                      (fake-stripe/event "invoice.upcoming" {"id" "in_1"}))]]]
          (let [{:keys [status body]} (handler request)]
            (println (format "    %-18s %s  %s" label status body)))))
      (println)
      (println (format "    payment is now: %s"
                       (:status (:found (payments/get-payment payments {:order-id order-id})))))
      (println))

    (println rule)
    (println)
    (println "  Some cards are neither taken nor refused.")
    (println)
    (let [held (order! ordering vanilla 1 "pm_card_authenticationRequired")]
      (system/relay-all! app)
      (let [payment (:found (payments/get-payment payments {:order-id held}))]
        (println (format "    %-22s %s" "status" (:status payment)))
        (println (format "    %-22s %s" "announced so far"
                         (count (:published (system/relay-payments! app)))))
        (println "    Nothing is told to anyone, because nothing has happened yet.")
        (println)
        (let [handler (system/handler app {:signing-secret fake-stripe/signing-secret
                                           :now #(quot (System/currentTimeMillis) 1000)})
              body    (fake-stripe/event "payment_intent.succeeded"
                                         {"id" (:gateway-reference payment)})]
          (handler (fake-stripe/signed-request body))
          (println (format "    the customer completes the challenge -> %s"
                           (:status (:found (payments/get-payment payments
                                                                  {:order-id held})))))
          (println (format "    announced now                          %s"
                           (count (:published (system/relay-payments! app)))))
          (println "    The callback settled it and announced it, in one")
          (println "    transaction. Two paths reach that conclusion and")
          (println "    UNIQUE (payment_id) makes sure only one announces."))))
    (println)

    (println rule)
    (println)
    (println "  A declined card is an answer, not an error.")
    (println)
    (let [receipts-before (count (fake-sendgrid/sent sendgrid))
          declined (order! ordering vanilla 1 "pm_card_chargeDeclined")]
      (system/relay-all! app)
      (let [payment (:found (payments/get-payment payments {:order-id declined}))]
        (println (format "    %-22s %s  %s" "status" (:status payment)
                         (:decline-reason payment)))
        (println (format "    %-22s %s" "further receipts sent"
                         (- (count (fake-sendgrid/sent sendgrid)) receipts-before))))
      (println "    No money moved, so nothing was announced and nobody"))
    (println "    was told their order was paid for.")
    (println)

    (println rule)
    (println)
    (println "  The failure that costs money: charged, then the lights go out.")
    (println)
    (let [crashing (system/start (assoc (postgres/config)
                                        :gateway {:provider :given
                                                  :instance (chaos/crash-after-authorize
                                                             (stripe-adapter/gateway
                                                              {:base-url (:base-url stripe)
                                                               :api-key "sk_test_lab29"}))}
                                        :emailer {:provider :memory})
                                 {:subscribe? false})
          order-id (order! (:ordering crashing) vanilla 3 "pm_card_visa")
          [delivery] (:published (system/relay-ordering! crashing))
          delivery (select-keys delivery [:headers :message])
          intents-before (count (fake-stripe/intents stripe))
          requests-before (count (fake-stripe/charges stripe))]
      (try (payments/charge! (:payments crashing) delivery)
           (catch Exception _ (println "    attempt 1   crashed after the charge")))
      (payments/charge! (:payments crashing) delivery)
      (println "    attempt 2   succeeded")
      (println)
      (println (format "    requests sent to stripe     %s"
                       (- (count (fake-stripe/charges stripe)) requests-before)))
      (println (format "    payment intents created     %s"
                       (- (count (fake-stripe/intents stripe)) intents-before)))
      (println (format "    idempotency keys used       %s"
                       (count (set (map :idempotency-key
                                        (drop requests-before
                                              (fake-stripe/charges stripe)))))))
      (println (format "    status                      %s"
                       (:status (:found (payments/get-payment (:payments crashing)
                                                              {:order-id order-id}))))))
    (println)
    (println "    Our payment id was written down before the first call and")
    (println "    sent as the Idempotency-Key on both. Stripe replayed the")
    (println "    original answer rather than taking the money again.")
    (println)

    (println rule)
    (println)
    (println "  The network is not reliable, so ask again.")
    (println)
    (let [before (count (fake-stripe/charges stripe))]
      (fake-stripe/fail-times! stripe 2)
      (let [held (order! ordering vanilla 1 "pm_card_visa")]
        (system/relay-all! app)
        (println "    provider failing:      2 x 503")
        (println (format "    requests sent:         %s"
                         (- (count (fake-stripe/charges stripe)) before)))
        (println (format "    payment:               %s"
                         (:status (:found (payments/get-payment payments
                                                                {:order-id held})))))
        (println "    One call by the caller, three by the adapter, backing off")
        (println "    with jitter, all carrying the same idempotency key.")))
    (println)

    (println rule)
    (println)
    (println "  Some messages will never be accepted.")
    (println)
    (let [broken (system/start (assoc (postgres/config)
                                      :gateway {:provider :memory}
                                      :emailer {:provider :memory})
                               {:subscribe? false})]
      (bus/subscribe! (:bus broken) :ordering/order-placed
                      (fn [_] (throw (ex-info "cannot handle this"
                                              {:reason :consumer-broken}))))
      (doseq [delivery (:published (system/relay-catalog! broken))]
        (ordering/receive! (:ordering broken) (select-keys delivery [:headers :message])))
      (order! (:ordering broken) vanilla 1 "pm_card_visa")
      (dotimes [pass relay/attempts-before-death]
        (let [{:keys [failed dead-lettered]} (system/relay-ordering! broken)]
          (println (format "    pass %s                 %s"
                           (inc pass)
                           (cond
                             (seq dead-lettered) "gave up -> dead letter"
                             (seq failed)        "failed, will try again"
                             :else               "nothing pending")))))
      (let [[dead] (ordering/dead-letters (:ordering broken))]
        (println (format "    graveyard:             %s after %s attempts"
                         (:message-type dead) (:attempts dead)))
        (println (format "    reason:                %s"
                         (subs (:last-error dead) 0 (min 44 (count (:last-error dead))))))
        (println (format "    queue now:             %s pending"
                         (count (:published (system/relay-ordering! broken)))))
        (ordering/revive! (:ordering broken) (:message-id dead) (:consumer dead))
        (println (format "    after revive:          %s pending again"
                         (count (:failed (system/relay-ordering! broken))))))
      (println "    The queue kept moving, the message kept its body, and")
      (println "    an operator has something to look at.")
      (println))

    (println rule)
    (println)
    (println "  The same care, against a provider with no idempotency key.")
    (println)
    (let [[settled] (:published (system/relay-payments! app))]
      (when settled
        (let [receipt (select-keys settled [:headers :message])
              before  (count (fake-sendgrid/sent sendgrid))]
          (notifications/receive! notifications receipt)
          (notifications/receive! notifications receipt)
          (println (format "    two deliveries of one fact -> %s extra email(s)"
                           (- (count (fake-sendgrid/sent sendgrid)) before))))))
    (println "    Our ledger stops a redelivery. It cannot stop a crash")
    (println "    between the send and the record -- SendGrid has no key to")
    (println "    carry. The port says so, and the tests prove it.")
    (println rule)
    (println)

    (fake-stripe/stop! stripe)
    (fake-sendgrid/stop! sendgrid))
  (system/stop-telemetry!)
  (shutdown-agents))
