(ns lab4.identity-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab4.identity :as id])
  (:import (java.util Random UUID)))

(deftest event-shape-test
  (testing "each event carries an identity alongside its type and data"
    (doseq [event id/events]
      (is (uuid? (:event/id event)))
      (is (= :flavour-sold (:event/type event)))
      (is (string? (get-in event [:data :flavour]))))))

(deftest command-shape-test
  (testing "each command carries an identity alongside its type and data"
    (doseq [command id/commands]
      (is (uuid? (:command/id command)))
      (is (= :buy-flavour (:command/type command)))
      (is (string? (get-in command [:data :flavour]))))))

(deftest message-shape-test
  (testing "each message carries a delivery identity, and the fact's id in its payload"
    (doseq [message id/messages]
      (is (uuid? (:message/id message)))
      (is (= :flavour-sold (:message/type message)))
      (is (uuid? (get-in message [:payload :event/id])))
      (is (string? (get-in message [:payload :flavour]))))))

(deftest event-identities-are-unique-test
  (testing "no two facts share an identity"
    (let [ids (map :event/id id/events)]
      (is (= (count ids) (count (distinct ids)))))))

(deftest same-sale-twice-is-two-facts-test
  (testing "identical type and data, distinguished only by identity"
    (is (= (:event/type id/flavour-sold-vanilla)
           (:event/type id/flavour-sold-vanilla-again)))
    (is (= (:data id/flavour-sold-vanilla)
           (:data id/flavour-sold-vanilla-again)))
    (is (not= (:event/id id/flavour-sold-vanilla)
              (:event/id id/flavour-sold-vanilla-again)))
    (is (not= id/flavour-sold-vanilla
              id/flavour-sold-vanilla-again))))

(deftest same-request-twice-is-one-request-test
  (testing "a retry reuses the command id, so the truck can recognise it"
    (is (= (:command/id id/buy-flavour-vanilla-command)
           (:command/id id/buy-flavour-vanilla-retry)))
    (is (= id/buy-flavour-vanilla-command
           id/buy-flavour-vanilla-retry))))

(deftest same-fact-twice-is-two-deliveries-test
  (testing "one fact told twice: two message ids, one event id"
    (is (not= (:message/id id/flavour-sold-vanilla-message)
              (:message/id id/flavour-sold-vanilla-message-again)))
    (is (= (get-in id/flavour-sold-vanilla-message [:payload :event/id])
           (get-in id/flavour-sold-vanilla-message-again [:payload :event/id])))
    (testing "so deduplicating on the message id would process the sale twice"
      (is (= 2 (count (distinct (map :message/id
                                     [id/flavour-sold-vanilla-message
                                      id/flavour-sold-vanilla-message-again]))))))
    (testing "while deduplicating on the event id recognises the repeat"
      (is (= 1 (count (distinct (map #(get-in % [:payload :event/id])
                                     [id/flavour-sold-vanilla-message
                                      id/flavour-sold-vanilla-message-again]))))))))

(deftest message-id-is-not-the-event-id-test
  (testing "the envelope's identity is its own, never borrowed from the fact"
    (doseq [message id/messages]
      (is (not= (:message/id message)
                (get-in message [:payload :event/id]))))))

(deftest uuid-v4-test
  (testing "version 4, and distinct on every call"
    (is (= 4 (.version ^UUID (id/uuid-v4))))
    (is (not= (id/uuid-v4) (id/uuid-v4)))))

(deftest uuid-v7-version-and-variant-test
  (let [uuid (id/uuid-v7 1700000000000 (Random. 42))]
    (is (= 7 (.version uuid)))
    (is (= 2 (.variant uuid)) "RFC 9562 variant bits, 0b10")))

(deftest uuid-v7-carries-its-timestamp-test
  (testing "the top 48 bits are the millisecond the id was minted"
    (let [millis 1700000000000
          uuid   (id/uuid-v7 millis (Random. 42))]
      (is (= millis (unsigned-bit-shift-right (.getMostSignificantBits uuid) 16))))))

(deftest uuid-v7-sorts-in-time-order-test
  (testing "ids minted later sort after ids minted earlier"
    (let [rng   (Random. 42)
          ids   (mapv #(id/uuid-v7 % rng)
                      [1700000000000 1700000000001 1700000000002 1700000001000])
          texts (mapv str ids)]
      (is (= texts (sort texts))))))

(deftest uuid-v7-is-deterministic-given-clock-and-rng-test
  (testing "same instant and same seed produce the same id"
    (is (= (id/uuid-v7 1700000000000 (Random. 42))
           (id/uuid-v7 1700000000000 (Random. 42))))))

(deftest constructors-take-their-identity-from-gen-id-test
  (testing "identity is supplied, not conjured, so the result is assertable"
    (let [event-id   #uuid "018f7a3e-0000-7000-8000-00000000beef"
          command-id #uuid "018f7a3d-0000-7000-8000-00000000cafe"]
      (is (= {:event/id   event-id
              :event/type :flavour-sold
              :data       {:flavour "vanilla"}}
             (id/flavour-sold (constantly event-id) "vanilla")))
      (is (= {:command/id   command-id
              :command/type :buy-flavour
              :data         {:flavour "vanilla"}}
             (id/buy-flavour (constantly command-id) "vanilla"))))))

(deftest flavour-sold-message-lifts-the-event-id-into-the-payload-test
  (let [message-id #uuid "018f7a3f-0000-7000-8000-00000000face"]
    (is (= {:message/id   message-id
            :message/type :flavour-sold
            :payload      {:event/id (:event/id id/flavour-sold-vanilla)
                           :flavour  "vanilla"}}
           (id/flavour-sold-message (constantly message-id)
                                    id/flavour-sold-vanilla)))))

(deftest uuid-v7-generator-test
  (testing "a generator built over a fake clock yields ordered ids"
    (let [tick   (atom 1700000000000)
          clock  #(swap! tick inc)
          gen-id (id/uuid-v7-generator clock (Random. 7))
          events [(id/flavour-sold gen-id "vanilla")
                  (id/flavour-sold gen-id "chocolate")
                  (id/flavour-sold gen-id "vanilla")]
          ids    (mapv (comp str :event/id) events)]
      (is (= ids (sort ids)))
      (is (= (count ids) (count (distinct ids)))))))
