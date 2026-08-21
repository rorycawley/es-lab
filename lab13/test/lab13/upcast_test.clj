(ns lab13.upcast-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab13.corpus :as corpus]
            [lab13.upcast :as upcast]))

(deftest every-shape-ever-written-still-reads-test
  (testing "the contract of an event store, asserted rather than assumed"
    (doseq [stored corpus/every-shape]
      (let [event (upcast/read-event stored)]
        (is (some? event))
        (is (= (select-keys stored [:event/id :event/type
                                    :stream/id :stream/version :event/position])
               (select-keys event [:event/id :event/type
                                   :stream/id :stream/version :event/position]))
            "identity and storage coordinates survive the ladder")))))

(deftest v1-reaches-the-current-shape-test
  (let [event (upcast/read-event corpus/flavour-sold-v1)]
    (is (= (upcast/current-version-of :flavour-sold)
           (get-in event [:metadata :schema-version]))
        "three hops, v1 → v2 → v3 → v4")
    (is (= "vanilla" (get-in event [:data :flavour]))
        "a keyword when it was written, a string by the time anybody folds it")
    (is (upcast/unknown-price? (get-in event [:data :unit-price])))))

(deftest v2-reaches-the-current-shape-test
  (let [event (upcast/read-event corpus/flavour-sold-v2)]
    (is (= (upcast/current-version-of :flavour-sold)
           (get-in event [:metadata :schema-version])))
    (testing "the rename moved the value without changing it"
      (is (= 2.50M (get-in event [:data :unit-price])))
      (is (nil? (get-in event [:data :price]))))))

(deftest a-current-event-passes-through-untouched-test
  (is (= corpus/flavour-sold-v4 (upcast/read-event corpus/flavour-sold-v4))))

(deftest upcasting-is-idempotent-test
  (testing "reading twice is reading once — nothing accumulates"
    (doseq [stored corpus/every-shape]
      (is (= (upcast/read-event stored)
             (upcast/read-event (upcast/read-event stored)))))))

(deftest upcasting-does-not-write-back-test
  (testing "the stored event is a fact; reading it does not amend it"
    (let [before corpus/flavour-sold-v1]
      (upcast/read-event before)
      (is (= 1 (get-in corpus/flavour-sold-v1 [:metadata :schema-version])))
      (is (= {:flavour :vanilla} (:data corpus/flavour-sold-v1)))
      (is (= before corpus/flavour-sold-v1)))))

(deftest the-missing-price-is-marked-not-invented-test
  (let [event (upcast/read-event corpus/flavour-sold-v1)
        price (get-in event [:data :unit-price])]
    (is (upcast/unknown-price? price))
    (testing "not zero, which would silently understate every historical total"
      (is (not= 0M price)))
    (testing "and not a plausible guess, which would be worse"
      (is (not (number? price))))))

(deftest each-type-climbs-its-own-ladder-test
  (testing "two types, two version numbers, and no relationship between them"
    ;; `:flavour-sold` is at 4 and `:flavour-sold-gross` at 2. They have never
    ;; shared a schema, so a single global version number would have been
    ;; describing nothing.
    (is (= 4 (upcast/current-version-of :flavour-sold)))
    (is (= 2 (upcast/current-version-of :flavour-sold-gross)))
    (is (= 2 (get-in (upcast/read-event corpus/flavour-sold-gross)
                     [:metadata :schema-version])))
    (testing "an unregistered type has unknown semantics"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown event type"
                            (upcast/current-version-of :truck-repainted))))))

(deftest a-step-that-forgets-to-raise-the-version-fails-loudly-test
  (testing "every rung must advance exactly one version"
    (defmethod upcast/upcast-step [::forgetful 1] [event] event)
    (try
      (with-redefs [upcast/current-version (assoc upcast/current-version ::forgetful 2)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"advance exactly one version"
             (upcast/read-event {:event/type ::forgetful
                                 :metadata   {:schema-version 1}}))))
      (finally (remove-method upcast/upcast-step [::forgetful 1])))))

(deftest unsupported-reader-inputs-fail-before-reaching-the-domain-test
  (testing "a writer deployed ahead of its reader"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"future schema version"
                          (upcast/read-event
                           (assoc-in corpus/flavour-sold-v4
                                     [:metadata :schema-version]
                                     5)))))
  (testing "a malformed or absent version"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid schema version"
                          (upcast/read-event
                           (update corpus/flavour-sold-v4 :metadata
                                   dissoc :schema-version)))))
  (testing "a type this semantic reader does not understand"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown event type"
                          (upcast/read-event {:event/type :freezer-failed
                                              :metadata {:schema-version 1}}))))
  (testing "a missing rung in a registered ladder"
    (with-redefs [upcast/current-version (assoc upcast/current-version ::missing 2)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing upcaster step"
                            (upcast/read-event {:event/type ::missing
                                                :metadata {:schema-version 1}}))))))

(deftest an-upcaster-cannot-change-the-recorded-envelope-test
  (defmethod upcast/upcast-step [::mutating 1]
    [event]
    (-> event
        (assoc :stream/id #uuid "0f1c2b3a-0000-4000-8000-000000000099")
        (assoc-in [:metadata :schema-version] 2)))
  (try
    (with-redefs [upcast/current-version (assoc upcast/current-version ::mutating 2)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"changed the recorded event envelope"
                            (upcast/read-event
                             {:event/id #uuid "018f7a3e-0000-7000-8000-000000000099"
                              :event/type ::mutating
                              :stream/id corpus/truck-1
                              :stream/version 1
                              :event/position 1
                              :metadata {:schema-version 1}}))))
    (finally (remove-method upcast/upcast-step [::mutating 1]))))
