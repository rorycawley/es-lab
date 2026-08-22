(ns lab29.demo
  (:gen-class)
  (:require [clojure.string :as str]
            [lab29.catalog.api :as catalog]
            [lab29.chaos :as chaos]
            [lab29.notifications.api :as notifications]
            [lab29.notifications.contract :as notifications-contract]
            [lab29.payments.adapter.stripe :as stripe-adapter]
            [lab29.platform.relay :as relay]
            [lab29.fake-sendgrid :as fake-sendgrid]
            [lab29.fake-stripe :as fake-stripe]
            [lab29.payments.api :as payments]
            [lab29.ordering.api :as ordering]
            [lab29.fake-subscriber :as subscriber]
            [lab29.websub.hub :as websub-hub]
            [lab29.websub.topics :as topics]
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

(defn- idle
  "A consumer that accepts and does nothing, so an act can drive the real one
  itself and control exactly how often."
  [_]
  {:accepted true})

(defn- to [summary consumer]
  (filterv #(= consumer (:consumer %)) (:delivered summary)))

(defn- quiesce!
  "Drain every queue until nothing moves.

  An act that measures \"how many receipts did *this* order cause\" has to
  start from a still system, or it counts the one before it. Relays are
  independent and each pass can create work for the next, so this loops
  rather than draining once."
  [app]
  (loop [guard 10]
    (let [moved (apply + (vals (system/relay-all! app)))]
      (when (and (pos? moved) (pos? guard))
        (recur (dec guard))))))

(defn- price-request [price-cents]
  {:command-id (random-uuid)
   :correlation-id (random-uuid)
   :product-id vanilla
   :product-name "vanilla"
   :price-cents price-cents})

(defn -main [& _]
  (recorder/start!)
  (postgres/truncate!)
  (let [stripe   (fake-stripe/start!)
        sendgrid (fake-sendgrid/start!)
        watcher  (subscriber/start!)
        app      (system/start (assoc (postgres/config)
                                      :base-url "http://registry.example"
                                      :gateway {:provider :stripe
                                                :base-url (:base-url stripe)
                                                :api-key "sk_test_lab29"}
                                      :emailer {:provider :sendgrid
                                                :base-url (:base-url sendgrid)
                                                :api-key "SG.lab29"}))
        {:keys [catalog ordering payments websub]} app]

    (println)
    (println "  Who is this message for?")
    (println rule)
    (println)
    (println "    one module, to act          command            exactly 1")
    (println "    any module, to know         integration event  0..N")
    (println "    me, right now               query              1 result")
    (println "    a stranger on the internet  WebSub             0..N, untrusted")
    (println)
    (println "    The routing table is derived from what the modules declare,")
    (println "    so a command with two handlers would not have started.")
    (println)

    (println rule)
    (println)
    (println "  One fact, two consumers, two delivery records.")
    (println)
    (catalog/change-price! catalog (price-request 300))
    (catalog/describe-product! catalog {:command-id (random-uuid)
                                        :correlation-id (random-uuid)
                                        :product-id vanilla
                                        :description "a creamy vanilla flavour"})
    (let [{:keys [delivered]} (system/relay-catalog! app)]
      (doseq [d delivered]
        (println (format "    tell  %-22s -> %s"
                         (name (get-in d [:message :event/type])) (name (:consumer d))))))
    (println)
    (println "    Ordering wants the price. WebSub wants the resource. Neither")
    (println "    knows the other exists, and neither can fail for the other.")
    (println)

    (println rule)
    (println)
    (println "  A request, a fact, a request, a fact.")
    (println)
    (let [order-id (order! ordering vanilla 2 "pm_card_visa")]
      (doseq [[label relay] [["ordering" system/relay-ordering!]
                             ["payments" system/relay-payments!]
                             ["ordering" system/relay-ordering!]]]
        (doseq [d (:delivered (relay app))]
          (let [m (:message d)]
            (println (format "    %-9s %-6s %-22s -> %s"
                             label
                             (if (:command/type m) "ask" "tell")
                             (name (or (:command/type m) (:event/type m)))
                             (name (:consumer d)))))))
      (println)
      (println (format "    payment: %s"
                       (:status (:found (payments/get-payment payments
                                                              {:order-id order-id}))))))
    (println)

    (println rule)
    (println)
    (println "  A subscriber outside the registry.")
    (println)
    (let [topic (topics/topic-url (:base-url app) vanilla)]
      (println (format "    topic     %s" topic))
      (println (format "    subscribe %s"
                       (if (:accepted (websub-hub/subscribe!
                                       (:websub-hub app)
                                       {:topic topic :callback (:callback watcher)
                                        :secret (:secret watcher) :lease-seconds 3600}))
                         "202 accepted, after the callback echoed our challenge"
                         "refused")))
      (println (format "    verified  %s" (subscriber/verifications watcher)))
      (println)
      (catalog/change-price! catalog (price-request 450))
      (system/relay-catalog! app)
      (let [[push] (subscriber/received watcher)]
        (println (format "    pushed    %s" (:body push)))
        (println (format "    signed    %s" (if (:genuine? push)
                                              "yes, with this subscriber's own secret"
                                              "no")))
        (println (format "    links     %s" (:links push))))
      (println)
      (println "    A topic is a resource, not a stream of facts. A subscriber")
      (println "    that misses a push and re-fetches is still correct, which")
      (println "    is exactly why this cannot be the internal bus.")
      (println))

    (println rule)
    (println)
    (println "  What a stranger was not shown.")
    (println)
    (let [public (topics/body (topics/representation (:datasource websub) vanilla))]
      (println (format "    the public resource:   %s" public))
      (println (format "    contains 'supplier':   %s"
                       (str/includes? public "supplier")))
      (println "    The cost price is in catalog.product and in no contract,")
      (println "    so no consumer could leak what it was never given."))
    (println rule)
    (println)

    ;; -----------------------------------------------------------------
    ;; None of what follows is lab 29's idea. It is lab 28's, running
    ;; unchanged beneath a messaging model that was rearranged around it --
    ;; which is the point worth showing: the delivery guarantees did not have
    ;; to be rebuilt when the semantics above them changed.
    ;; -----------------------------------------------------------------

    (println "  Underneath all of it, still: the reliability lab 28 built.")
    (println rule)
    (println)
    (println "  Stripe calls back. Anyone can post to that endpoint.")
    (println)
    (let [handler   (system/handler app {:signing-secret fake-stripe/signing-secret
                                         :now #(quot (System/currentTimeMillis) 1000)})
          order-id  (order! ordering vanilla 1 "pm_card_visa")
          _         (system/relay-all! app)
          reference (:gateway-reference (:found (payments/get-payment
                                                 payments {:order-id order-id})))
          event     (fake-stripe/event "payment_intent.succeeded" {"id" reference})
          signed    (fake-stripe/signed-request event)]
      (doseq [[label request]
              [["unsigned"         (assoc signed :headers {})]
               ["forged"           (assoc-in signed [:headers "stripe-signature"]
                                             (stripe-adapter/sign
                                              "whsec_attacker"
                                              (quot (System/currentTimeMillis) 1000)
                                              (:body signed)))]
               ["genuine"          signed]
               ["genuine, again"   signed]
               ["a type we ignore" (fake-stripe/signed-request
                                    (fake-stripe/event "invoice.upcoming" {"id" "in_1"}))]]]
        (let [{:keys [status body]} (handler request)]
          (println (format "    %-18s %s  %s" label status body))))
      (println)
      (println (format "    payment is now: %s"
                       (:status (:found (payments/get-payment payments
                                                              {:order-id order-id}))))))
    (println)
    (println "    A duplicate is answered 200, because a provider retries any")
    (println "    non-2xx forever and a 500 there is a loop that never ends.")
    (println)

    (println rule)
    (println)
    (println "  A declined card is an answer, not an error.")
    (println)
    (quiesce! app)
    (let [receipts-before (count (fake-sendgrid/sent sendgrid))
          declined        (order! ordering vanilla 1 "pm_card_chargeDeclined")]
      (quiesce! app)
      (let [payment (:found (payments/get-payment payments {:order-id declined}))]
        (println (format "    %-22s %s  %s" "status" (:status payment)
                         (:decline-reason payment)))
        (println (format "    %-22s %s" "further receipts sent"
                         (- (count (fake-sendgrid/sent sendgrid)) receipts-before)))))
    (println "    No money moved, so nothing was announced and nobody was told")
    (println "    their order was paid for.")
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
    (println "  The failure that costs money: charged, then the lights go out.")
    (println)
    (let [crashing (system/start
                    (assoc (postgres/config)
                           :base-url "http://registry.example"
                           :gateway {:provider :given
                                     :instance (chaos/crash-after-authorize
                                                (stripe-adapter/gateway
                                                 {:base-url (:base-url stripe)
                                                  :api-key "sk_test_lab29"}))}
                           :emailer {:provider :memory})
                    {:handlers {:payments idle :notifications idle}})
          order-id        (order! (:ordering crashing) vanilla 3 "pm_card_visa")
          [delivery]      (to (system/relay-ordering! crashing) :payments)
          delivery        (select-keys delivery [:headers :message])
          intents-before  (count (fake-stripe/intents stripe))
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
    (println "  Some deliveries will never be accepted.")
    (println)
    (let [broken (system/start
                  (assoc (postgres/config)
                         :base-url "http://registry.example"
                         :gateway {:provider :memory}
                         :emailer {:provider :memory})
                  {:handlers {:ordering (fn [_]
                                          (throw (ex-info "cannot handle this"
                                                          {:reason :consumer-broken})))
                              :websub   idle}})]
      (catalog/change-price! (:catalog broken) (price-request 300))
      (dotimes [pass relay/attempts-before-death]
        (let [{:keys [failed dead-lettered]} (system/relay-catalog! broken)]
          (println (format "    pass %s                 %s"
                           (inc pass)
                           (cond
                             (seq dead-lettered) "gave up -> dead letter"
                             (seq failed)        "failed, will try again"
                             :else               "nothing pending")))))
      (let [[dead] (catalog/dead-letters (:catalog broken))]
        (println (format "    graveyard:             %s for %s"
                         (:message-type dead) (name (:consumer dead))))
        (println (format "    reason:                %s"
                         (subs (:last-error dead) 0 (min 44 (count (:last-error dead))))))
        (println (format "    other consumers:       %s unaffected"
                         (name :websub)))
        (catalog/revive! (:catalog broken) (:message-id dead) (:consumer dead))
        (println (format "    after revive:          %s pending again"
                         (count (:failed (system/relay-catalog! broken)))))))
    (println "    The queue kept moving, the message kept its body, and an")
    (println "    operator has something to look at.")
    (println)

    (println rule)
    (println)
    (println "  The same care, against a provider with no idempotency key.")
    (println)
    (let [[settled] (to (system/relay-payments! app) :ordering)]
      (if-not settled
        (println "    nothing settled in this run")
        (let [order-id (get-in settled [:message :payload :order-id])
              amount   (get-in settled [:message :payload :amount-cents])
              receipt  {:headers {}
                        :message (notifications-contract/send-receipt
                                  (random-uuid) (random-uuid) (random-uuid)
                                  (random-uuid) order-id amount customer)}
              before   (count (fake-sendgrid/sent sendgrid))]
          (notifications/receive! (:notifications app) receipt)
          (notifications/receive! (:notifications app) receipt)
          (println (format "    deliveries of one send-receipt command  %s" 2))
          (println (format "    emails the customer actually received   %s"
                           (- (count (fake-sendgrid/sent sendgrid)) before))))))
    (println)
    (println "    The ledger stopped the second delivery, because that one is")
    (println "    on our side of the wire. It cannot stop a crash between the")
    (println "    send and the record -- SendGrid has no idempotency key for a")
    (println "    retry to carry. The port says so, and the tests prove it.")
    (println rule)
    (println)

    (subscriber/stop! watcher)
    (fake-stripe/stop! stripe)
    (fake-sendgrid/stop! sendgrid))
  (system/stop-telemetry!)
  (shutdown-agents))
