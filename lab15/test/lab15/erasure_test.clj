(ns lab15.erasure-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lab15.application :as application]
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

(def subject-key (vault/generate-key))
(def held (vault/hold vault/empty-vault customer subject-key))
(def shredded (vault/destroy held customer))

;; A card is issued to a named person, then that person buys three cones.
;; Only the first event describes anybody.
(def log
  (-> []
      (application/handle-card held gen-id t0
                               (command :issue-card {:card-id     card
                                                     :customer-id customer
                                                     :personal    personal}))
      (application/handle-truck gen-id t0
                                (command :load-truck {:truck-id truck
                                                      :flavour "vanilla"
                                                      :quantity 10}))
      (as-> l (reduce (fn [acc _]
                        (application/handle-truck
                         acc gen-id t0
                         (command :buy-flavour {:truck-id    truck
                                                :flavour     "vanilla"
                                                :customer-id customer})))
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
          "no repeated name or email"))))

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

(deftest missing-key-does-not-disguise-an-unsupported-envelope-test
  (let [card-event (first (store/stream log card))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported sealed-value version"
                          (reading/read-event
                           shredded
                           (assoc-in card-event [:data :personal :crypto/version] 2))))))

(deftest erasure-changes-the-vault-not-the-store-test
  (testing "the ciphertext is still there after erasure — just unopenable"
    (let [sealed (get-in (first (store/stream log card)) [:data :personal])]
      (is (contains? sealed :ciphertext))
      (is (contains? sealed :iv))
      (is (= 1 (:crypto/version sealed)))
      (is (= "AES-256-GCM" (:algorithm sealed)))
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
        other-card #uuid "0f1c2b3a-0000-4000-8000-0000000000c2"
        log'   (application/handle-card
                log both gen-id t0
                (command :issue-card {:card-id     other-card
                                      :customer-id other
                                      :personal    {:name "Cormac"}}))
        after  (projection/rebuild (vault/destroy both customer) log')]
    (is (reading/erased? (projection/name-of after customer)))
    (is (= "Cormac" (projection/name-of after other)))))

(deftest the-domain-proposes-plaintext-and-the-application-protects-it-test
  (let [proposal (first (domain/decide-card
                         (command :issue-card {:card-id     card
                                               :customer-id customer
                                               :personal    personal})
                         domain/initial-card))
        stored   (first (store/stream log card))]
    (is (= personal (get-in proposal [:data :personal]))
        "the pure domain knows no encryption technology")
    (is (not= personal (get-in stored [:data :personal])))
    (is (= personal (get-in (reading/read-event held stored) [:data :personal])))))

(deftest unknown-semantics-fail-at-each-reader-test
  (let [unknown {:event/type :customer-profile-exported :data {}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown event type"
                          (reading/read-event held unknown)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown event type"
                          (projection/apply-event projection/initial-model unknown)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown card event type"
                          (domain/replay-card [unknown])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown truck event type"
                          (domain/replay-truck [unknown])))))

(deftest the-application-identifies-before-persistence-test
  (let [event-id #uuid "018f7a3e-0000-7000-8000-000000002001"
        cmd       {:command/id     #uuid "0f1c2b3a-0000-4000-8000-000000001101"
                   :command/type   :load-truck
                   :correlation-id #uuid "cc79c083-0000-4000-8000-000000000011"
                   :data           {:truck-id truck :flavour "vanilla" :quantity 2}}
        event     (first (application/handle-truck [] (constantly event-id) t0 cmd))]
    (is (= event-id (:event/id event)))
    (is (= t0 (:event/occurred-at event)))
    (is (= (:command/id cmd) (get-in event [:metadata :causation-id])))
    (is (= (:correlation-id cmd) (get-in event [:metadata :correlation-id])))
    (is (= 1 (:stream/version event)))
    (is (= 1 (:event/position event)))))

(deftest the-write-edge-refuses-plaintext-append-without-an-active-key-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No active key for subject"
                        (application/handle-card
                         [] shredded gen-id t0
                         (command :issue-card {:card-id     card
                                               :customer-id customer
                                               :personal    personal}))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid event id"
                        (application/handle-truck
                         [] (constantly "not-a-uuid") t0
                         (command :load-truck {:truck-id truck
                                               :flavour "vanilla"
                                               :quantity 1})))))
