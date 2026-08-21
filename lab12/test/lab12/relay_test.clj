(ns lab12.relay-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab12.consumer :as consumer]
            [lab12.relay :as relay]
            [lab12.store :as store]
            [lab12.truck :as truck]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def t0 #inst "2026-08-16T09:00:00.000-00:00")

(defn- gen-id [] (random-uuid))

(defn- command [type conversation data]
  {:command/id (random-uuid) :command/type type
   :correlation-id conversation :data data})

(defn- handle [log cmd]
  (let [stream-id (get-in cmd [:data :truck-id])
        state     (truck/replay (store/stream log stream-id))]
    (store/append log stream-id (store/current-version log stream-id)
                  gen-id t0 cmd (truck/decide cmd state))))

(def conversation (random-uuid))

;; One cone loaded and sold. The sale empties the truck, so the log ends with
;; a depletion — the only fact in it that anybody outside is told about.
(def log
  (-> []
      (handle (command :load-truck conversation
                       {:truck-id truck-1 :flavour "vanilla" :quantity 1}))
      (handle (command :buy-flavour conversation {:truck-id truck-1 :flavour "vanilla"}))))

(deftest the-log-holds-three-facts-and-publishes-two-messages-test
  (is (= [:truck-loaded :flavour-sold :stock-depleted] (map :event/type log)))
  (let [{:keys [sent]} (relay/run-once log 0 relay/empty-broker gen-id)]
    (is (= 2 (count sent)) "only the depletion is anyone else's business")
    (is (= [:flavour-unavailable :restock-required] (map :message/type sent)))))

(deftest the-relay-stamps-a-delivery-identity-test
  (let [{:keys [sent]} (relay/run-once log 0 relay/empty-broker gen-id)]
    (doseq [message sent]
      (is (uuid? (:message/id message)))
      (is (uuid? (get-in message [:payload :event/id]))))
    (testing "two messages about one fact: two envelopes, one event id"
      (is (= 2 (count (distinct (map :message/id sent)))))
      (is (= 1 (count (distinct (map #(get-in % [:payload :event/id]) sent))))))))

(deftest the-relay-carries-the-conversation-across-the-boundary-test
  (let [{:keys [sent]} (relay/run-once log 0 relay/empty-broker gen-id)]
    (doseq [message sent]
      (is (= conversation (get-in message [:metadata :correlation-id]))))))

(deftest nothing-new-means-nothing-published-test
  (let [{:keys [broker checkpoint]} (relay/run-once log 0 relay/empty-broker gen-id)
        again (relay/run-once log checkpoint broker gen-id)]
    (is (= [] (:sent again)))
    (is (= broker (:broker again)))))

(deftest a-crash-before-the-checkpoint-publishes-twice-test
  (testing "sent, then died before recording how far it got"
    (let [first-pass  (relay/run-once log 0 relay/empty-broker gen-id)
          second-pass (relay/run-once log 0 (:broker first-pass) gen-id)
          delivered   (get-in second-pass [:broker :delivered])]
      (is (= 4 (count delivered)) "the same two facts, delivered twice")
      (testing "each delivery has its own envelope id"
        (is (= 4 (count (distinct (map :message/id delivered))))))
      (testing "but they announce only one fact"
        (is (= 1 (count (distinct (map #(get-in % [:payload :event/id]) delivered)))))))))

(deftest the-consumer-absorbs-the-duplicates-test
  (let [first-pass  (relay/run-once log 0 relay/empty-broker gen-id)
        second-pass (relay/run-once log 0 (:broker first-pass) gen-id)
        delivered   (get-in second-pass [:broker :delivered])
        once  (consumer/receive-all consumer/initial-model (take 2 delivered))
        twice (consumer/receive-all consumer/initial-model delivered)]
    (is (= #{"vanilla"} (:unavailable once)))
    (is (= once twice) "four deliveries, same model as two")))

(deftest deduplicating-on-the-envelope-would-not-work-test
  (testing "the two deliveries of one fact have different :message/id values"
    (let [first-pass  (relay/run-once log 0 relay/empty-broker gen-id)
          second-pass (relay/run-once log 0 (:broker first-pass) gen-id)
          delivered   (get-in second-pass [:broker :delivered])
          by-envelope (fn [model message]
                        (if (contains? (:seen model) (:message/id message))
                          model
                          (-> model
                              (update :unavailable conj (get-in message [:payload :flavour]))
                              (update :seen conj (:message/id message)))))]
      (is (= 4 (count (:seen (reduce by-envelope consumer/initial-model delivered))))
          "every delivery looks new, so nothing is suppressed"))))

(deftest the-relay-catches-up-incrementally-test
  (testing "a second sale, published on the next pass only"
    (let [{:keys [broker checkpoint]} (relay/run-once log 0 relay/empty-broker gen-id)
          log' (-> log
                   (handle (command :load-truck conversation
                                    {:truck-id truck-1 :flavour "chocolate" :quantity 1}))
                   (handle (command :buy-flavour conversation
                                    {:truck-id truck-1 :flavour "chocolate"})))
          next-pass (relay/run-once log' checkpoint broker gen-id)]
      (is (= 2 (count (:sent next-pass))) "only the new depletion")
      (is (= #{"chocolate"} (set (map #(get-in % [:payload :flavour]) (:sent next-pass))))))))
