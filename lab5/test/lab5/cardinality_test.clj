(ns lab5.cardinality-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab5.cardinality :as c]))

(deftest a-command-produces-zero-one-or-many-events-test
  (testing "refused: nothing happened, so there is no fact"
    (is (= 0 (c/events-produced c/refused))))
  (testing "the ordinary case, and the only one the earlier labs showed"
    (is (= 1 (c/events-produced c/ordinary-sale))))
  (testing "the last cone is two facts caused by one request"
    (is (= 2 (c/events-produced c/last-cone-sale)))))

(deftest the-order-of-produced-events-is-significant-test
  (testing "emitted in the order the facts became true: the sale caused the depletion"
    (is (= [:flavour-sold :stock-depleted]
           (map :event/type (:events c/last-cone-sale)))))
  (testing "a vector, not a set — the order is what gets appended and replayed"
    (is (vector? (:events c/last-cone-sale)))))

(deftest a-refusal-is-not-an-event-test
  (testing "the absence of an event, not an event named :refused"
    (is (empty? (:events c/refused)))
    (is (not-any? #(= :buy-flavour-refused (:event/type %))
                  (mapcat :events c/decisions)))))

(deftest many-events-from-one-command-are-distinct-facts-test
  (let [events (:events c/last-cone-sale)]
    (is (= 2 (count (distinct (map :event/id events)))))
    (is (= 2 (count (distinct (map :event/type events)))))))

(deftest an-event-produces-zero-one-or-many-messages-test
  (testing "publishing is a decision, and the default is not to"
    (is (= 0 (c/messages-produced c/kept-private))))
  (testing "one fact, two contracts, two audiences"
    (is (= 2 (c/messages-produced c/fanned-out)))
    (is (= [:stock-depleted :flavour-unavailable]
           (map :message/type (:messages c/fanned-out))))))

(deftest fan-out-shares-one-event-id-across-many-message-ids-test
  (let [messages (:messages c/fanned-out)]
    (is (= 2 (count (distinct (map :message/id messages))))
        "each delivery is its own envelope")
    (is (= 1 (count (distinct (map #(get-in % [:payload :event/id]) messages))))
        "but they all announce the same fact")))

(deftest the-counts-are-independent-test
  (testing "a command with two events need not produce any messages at all"
    (is (= 2 (c/events-produced c/last-cone-sale)))
    (is (= 0 (c/messages-produced c/kept-private)))
    (is (= (:event/id (first (:events c/last-cone-sale)))
           (:event/id (:event c/kept-private))))))

(deftest an-event-has-exactly-one-cause-test
  (testing "the fan is one-way: many events from one command, never the reverse"
    (doseq [event (mapcat :events c/decisions)]
      (is (= 1 (count (c/causes c/decisions (:event/id event)))))))
  (testing "and both facts from the last sale name the same command"
    (is (= 1 (->> (:events c/last-cone-sale)
                  (mapcat #(c/causes c/decisions (:event/id %)))
                  (map :command/id)
                  distinct
                  count)))))

(deftest an-unknown-event-has-no-cause-test
  (is (= [] (c/causes c/decisions (random-uuid)))))

(deftest every-count-in-the-lab-is-zero-one-or-many-test
  (testing "nothing here assumes a matched set"
    (is (= [0 1 2] (sort (map c/events-produced c/decisions))))
    (is (= [0 2] (sort (map c/messages-produced c/publications))))))
