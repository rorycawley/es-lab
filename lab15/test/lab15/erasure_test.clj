(ns lab15.erasure-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lab15.domain :as domain]
            [lab15.projection :as projection]
            [lab15.reading :as reading]
            [lab15.store :as store]
            [lab15.vault :as vault]))

(def truck    #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def card     #uuid "0f1c2b3a-0000-4000-8000-0000000000c1")
(def customer "C-123")
(def personal {:name "Aoife Ní Bhriain" :email "aoife@example.ie"})

(def t0 #inst "2026-08-16T09:00:00.000-00:00")

(defn- gen-id [] (random-uuid))

(defn- command [type data]
  {:command/id (random-uuid) :command/type type
   :correlation-id (random-uuid) :data data})

(defn- append [log stream-id decide cmd]
  (store/append log stream-id (store/current-version log stream-id)
                gen-id t0 cmd (decide cmd nil)))

(def subject-key (vault/generate-key))
(def held (vault/hold vault/empty-vault customer subject-key))
(def shredded (vault/destroy held customer))

;; A card is issued to a named person, then that person buys three cones.
;; Only the first event describes anybody.
(def log
  (-> []
      (append card  #(domain/decide-card %1 %2)
              (command :issue-card {:customer-id customer
                                    :key         subject-key
                                    :personal    personal}))
      (store/append truck (store/current-version [] truck) gen-id t0
                    (command :load-truck {})
                    [{:event/type :truck-loaded :data {:flavour "vanilla" :quantity 10}}])
      (as-> l (reduce (fn [acc _]
                        (store/append acc truck (store/current-version acc truck)
                                      gen-id t0 (command :buy-flavour {})
                                      [{:event/type :flavour-sold
                                        :data {:flavour "vanilla" :customer-id customer}}]))
                      l (range 3)))))

;; ---------------------------------------------------------------------------
;; Separation: most events never described the person in the first place.
;; ---------------------------------------------------------------------------

(deftest sales-name-a-customer-and-describe-nobody-test
  (let [sales (filter #(= :flavour-sold (:event/type %)) log)]
    (is (= 3 (count sales)))
    (doseq [sale sales]
      (is (= customer (get-in sale [:data :customer-id])))
      (is (= #{:flavour :customer-id} (set (keys (:data sale))))
          "no name, no email, nothing to erase"))))

(deftest only-one-event-in-the-whole-log-holds-personal-data-test
  (is (= 1 (count (filter #(get-in % [:data :personal]) log)))))

(deftest the-plaintext-is-nowhere-in-the-log-test
  (let [text (pr-str log)]
    (is (not (str/includes? text "Aoife")))
    (is (not (str/includes? text "example.ie")))))

;; ---------------------------------------------------------------------------
;; Shredding
;; ---------------------------------------------------------------------------

(deftest with-the-key-the-name-reads-back-test
  (let [card-event (first (store/stream log card))]
    (is (= personal (get-in (reading/read-event held card-event) [:data :personal])))))

(deftest without-the-key-it-reads-as-erased-test
  (let [card-event (first (store/stream log card))]
    (is (reading/erased? (get-in (reading/read-event shredded card-event)
                                 [:data :personal])))))

(deftest erasure-changes-the-vault-not-the-store-test
  (testing "the ciphertext is still there after erasure — just unopenable"
    (let [sealed (get-in (first (store/stream log card)) [:data :personal])]
      (is (contains? sealed :ciphertext))
      (is (contains? sealed :iv))
      (is (reading/erased? (get-in (reading/read-event shredded
                                                       (first (store/stream log card)))
                                   [:data :personal]))))))

(deftest deleting-the-event-would-have-broken-the-stream-test
  (testing "the option this lab does not take, and why"
    (let [truck-stream (store/stream log truck)
          without      (vec (remove #(= 2 (:stream/version %)) truck-stream))
          versions     (map :stream/version without)]
      (is (= [1 3 4] versions) "a hole where lab 7 asserts contiguous versions")
      (is (not= (range 1 (inc (count versions))) versions))
      (is (not= (domain/replay-truck truck-stream) (domain/replay-truck without))
          "and replay now yields a history that never happened"))))

(deftest the-stream-stays-intact-test
  (testing "deleting the event would have broken lab 7's contiguity; this does not"
    (let [versions (map :stream/version (store/stream log truck))]
      (is (= (range 1 (inc (count versions))) versions)))
    (is (= 5 (count log)) "same number of events before and after erasure")))

(deftest the-facts-survive-the-identity-test
  (testing "what was sold is business record; who they were is not"
    (let [before (domain/replay-truck (store/stream log truck))
          after  (domain/replay-truck (reading/read-all shredded (store/stream log truck)))]
      (is (= {"vanilla" 7} before))
      (is (= before after) "three cones sold, before and after erasure"))))

(deftest the-card-is-still-known-to-have-been-issued-test
  (let [state (domain/replay-card (reading/read-all shredded (store/stream log card)))]
    (is (= :active (:status state)))
    (is (= customer (:customer-id state)))
    (is (reading/erased? (:personal state)) "who, and only who, is gone")))

;; ---------------------------------------------------------------------------
;; The leak: a read model built before the key was destroyed.
;; ---------------------------------------------------------------------------

(deftest a-projection-built-before-erasure-still-holds-the-name-test
  (let [model (projection/rebuild held log)]
    (is (= "Aoife Ní Bhriain" (projection/name-of model customer)))
    (testing "and destroying the key does nothing to it"
      (is (= "Aoife Ní Bhriain" (projection/name-of model customer))
          "the copy was made while the key existed, in a store nobody encrypted"))))

(deftest rebuilding-is-what-carries-erasure-to-the-read-side-test
  (let [stale (projection/rebuild held log)
        fresh (projection/rebuild shredded log)]
    (is (= "Aoife Ní Bhriain" (projection/name-of stale customer)))
    (is (reading/erased? (projection/name-of fresh customer)))
    (testing "and the sales history is identical in both"
      (is (= (:sales stale) (:sales fresh)))
      (is (= 3 (count (get-in fresh [:sales customer])))))))

(deftest erasure-reaches-only-the-subject-asked-for-test
  (let [other  "C-999"
        other-key (vault/generate-key)
        both   (-> held (vault/hold other other-key))
        log'   (append log card #(domain/decide-card %1 %2)
                       (command :issue-card {:customer-id other
                                             :key         other-key
                                             :personal    {:name "Cormac"}}))
        after  (projection/rebuild (vault/destroy both customer) log')]
    (is (reading/erased? (projection/name-of after customer)))
    (is (= "Cormac" (projection/name-of after other)))))
