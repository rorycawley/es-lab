(ns lab29.demo
  (:gen-class)
  (:require [clojure.string :as str]
            [lab29.catalog.api :as catalog]
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

    (subscriber/stop! watcher)
    (fake-stripe/stop! stripe)
    (fake-sendgrid/stop! sendgrid))
  (system/stop-telemetry!)
  (shutdown-agents))
