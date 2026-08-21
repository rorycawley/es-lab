(ns lab12.relay-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab12.consumer :as consumer]
            [lab12.handler :as handler]
            [lab12.relay :as relay]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def conversation #uuid "cc79c083-0000-4000-8000-000000000012")

(def load-command-id
  #uuid "0f1c2b3a-0000-4000-8000-000000001201")

(def buy-command-id
  #uuid "0f1c2b3a-0000-4000-8000-000000001202")

(def load-chocolate-command-id
  #uuid "0f1c2b3a-0000-4000-8000-000000001203")

(def buy-chocolate-command-id
  #uuid "0f1c2b3a-0000-4000-8000-000000001204")

(def depletion-event-id
  #uuid "018f7a3e-0000-7000-8000-000000001003")

(def first-message-id
  #uuid "018f7a3e-0000-7000-8000-000000002001")

(def t0 #inst "2026-08-16T09:00:00.000-00:00")

(defn- counting-ids
  "A deterministic fake for an identifier port."
  [start]
  (let [counter (atom start)]
    (fn []
      (java.util.UUID/fromString
       (format "018f7a3e-0000-7000-8000-%012d" (swap! counter inc))))))

(defn- command [command-id type data]
  {:command/id     command-id
   :command/type   type
   :correlation-id conversation
   :data           data})

(defn- sold-out-log []
  ;; One cone loaded and sold. The depletion is the only fact with an
  ;; integration contract.
  (let [gen-id (counting-ids 1000)]
    (-> []
        (handler/handle gen-id t0
                        (command load-command-id
                                 :load-truck
                                 {:truck-id truck-1
                                  :flavour "vanilla"
                                  :quantity 1}))
        (handler/handle gen-id t0
                        (command buy-command-id
                                 :buy-flavour
                                 {:truck-id truck-1
                                  :flavour "vanilla"})))))

(deftest the-log-holds-three-facts-and-publishes-two-addressed-messages-test
  (let [log (sold-out-log)
        {:keys [sent checkpoint]}
        (relay/run-once log 0 relay/empty-broker (counting-ids 2000))]
    (is (= [:truck-loaded :flavour-sold :stock-depleted]
           (map :event/type log)))
    (is (= 3 checkpoint))
    (is (= [:flavour-unavailable :restock-required]
           (map :message/type sent)))
    (is (= [:customer-app :purchasing] (map :recipient sent)))))

(deftest the-relay-creates-complete-envelope-identities-test
  (let [{:keys [sent]}
        (relay/run-once (sold-out-log) 0 relay/empty-broker (counting-ids 2000))]
    (is (= first-message-id (:message/id (first sent))))
    (is (every? uuid? (map :message/id sent)))
    (testing "two messages about one fact: two envelopes, one event id"
      (is (= 2 (count (distinct (map :message/id sent)))))
      (is (= #{depletion-event-id}
             (set (map #(get-in % [:payload :event/id]) sent)))))))

(deftest the-relay-propagates-correlation-and-adds-immediate-causation-test
  (let [{:keys [sent]}
        (relay/run-once (sold-out-log) 0 relay/empty-broker (counting-ids 2000))]
    (doseq [message sent]
      (is (= conversation (get-in message [:metadata :correlation-id])))
      (is (= depletion-event-id (get-in message [:metadata :causation-id]))))))

(deftest nothing-new-means-nothing-published-test
  (let [log (sold-out-log)
        {:keys [broker checkpoint]}
        (relay/run-once log 0 relay/empty-broker (counting-ids 2000))
        again (relay/run-once log checkpoint broker (counting-ids 3000))]
    (is (= [] (:sent again)))
    (is (= broker (:broker again)))))

(deftest a-crash-before-the-checkpoint-republishes-in-new-envelopes-test
  (testing "published, then died before recording how far it got"
    (let [log         (sold-out-log)
          first-pass  (relay/run-once log 0 relay/empty-broker (counting-ids 2000))
          second-pass (relay/run-once log 0 (:broker first-pass) (counting-ids 3000))
          delivered   (get-in second-pass [:broker :delivered])]
      (is (= 4 (count delivered)) "two contracts for one fact, published twice")
      (is (= 4 (count (distinct (map :message/id delivered))))
          "each new envelope has its own identity")
      (is (= #{depletion-event-id}
             (set (map #(get-in % [:payload :event/id]) delivered)))))))

(deftest the-customer-consumer-absorbs-only-its-duplicate-test
  (let [log         (sold-out-log)
        first-pass  (relay/run-once log 0 relay/empty-broker (counting-ids 2000))
        second-pass (relay/run-once log 0 (:broker first-pass) (counting-ids 3000))
        delivered   (get-in second-pass [:broker :delivered])
        once  (consumer/receive-all consumer/initial-model (take 2 delivered))
        twice (consumer/receive-all consumer/initial-model delivered)]
    (is (= #{"vanilla"} (:unavailable once)))
    (is (= #{depletion-event-id} (:seen once)))
    (is (= once twice))))

(deftest another-recipients-message-cannot-poison-this-consumers-inbox-test
  (let [{:keys [sent]}
        (relay/run-once (sold-out-log) 0 relay/empty-broker (counting-ids 2000))
        purchasing-first (reverse sent)
        model (consumer/receive-all consumer/initial-model purchasing-first)]
    (is (= #{"vanilla"} (:unavailable model)))
    (is (= #{depletion-event-id} (:seen model)))))

(deftest deduplicating-on-the-envelope-would-not-suppress-a-republish-test
  (let [log         (sold-out-log)
        first-pass  (relay/run-once log 0 relay/empty-broker (counting-ids 2000))
        second-pass (relay/run-once log 0 (:broker first-pass) (counting-ids 3000))
        delivered   (->> (get-in second-pass [:broker :delivered])
                         (filter #(= :customer-app (:recipient %))))
        seen-envelope-ids (set (map :message/id delivered))]
    (is (= 2 (count seen-envelope-ids))
        "both envelopes look new although they carry the same fact")))

(deftest the-relay-catches-up-incrementally-test
  (let [log (sold-out-log)
        {:keys [broker checkpoint]}
        (relay/run-once log 0 relay/empty-broker (counting-ids 2000))
        event-ids (counting-ids 1100)
        log' (-> log
                 (handler/handle event-ids t0
                                 (command load-chocolate-command-id
                                          :load-truck
                                          {:truck-id truck-1
                                           :flavour "chocolate"
                                           :quantity 1}))
                 (handler/handle event-ids t0
                                 (command buy-chocolate-command-id
                                          :buy-flavour
                                          {:truck-id truck-1
                                           :flavour "chocolate"})))
        next-pass (relay/run-once log' checkpoint broker (counting-ids 3000))]
    (is (= 2 (count (:sent next-pass))))
    (is (= #{"chocolate"}
           (set (map #(get-in % [:payload :flavour]) (:sent next-pass)))))))

(deftest invalid-envelope-or-consumer-semantics-fail-loudly-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid message id"
                        (relay/run-once (sold-out-log)
                                        0
                                        relay/empty-broker
                                        (constantly "not-a-uuid"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown message type"
                        (consumer/receive consumer/initial-model
                                          {:message/type :flavour-renamed
                                           :recipient :customer-app
                                           :payload {:event/id depletion-event-id}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid event id"
                        (consumer/receive consumer/initial-model
                                          {:message/type :flavour-unavailable
                                           :recipient :customer-app
                                           :payload {:event/id nil
                                                     :flavour "vanilla"}}))))
