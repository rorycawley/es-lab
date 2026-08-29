(ns decider.identity-test
  "Semantic identity — README Part VIII.

   Three result shapes carry `:spec/ref`, and until these tests existed only
   one of them was checked against anything. A fourth identity field added to
   `decider.identity/specification-ref` but not to
   `decider.schema/SpecificationRef` — or attached to decisions but not to
   invalid-input results — would have been a silent divergence."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [decider.bundle :as bundle]
   [decider.core :as decider]
   [decider.hash :as hash]
   [decider.identity :as identity]
   [decider.schema :as schema]
   [malli.core :as m]))

(def specification
  (bundle/load "semantic-bundles/ticketmaster-reserve-tickets.edn"))

(def valid-state
  {:performance-id "oasis-dublin-2026"
   :sale-status :open
   :tickets-remaining 100
   :max-tickets-per-customer 4
   :customer-id->tickets-reserved {"customer-1" 2}})

(def valid-command
  {:command/type :reserve-tickets
   :data {:customer-id "customer-1"
          :quantity 1}})

(deftest constructed-reference-matches-its-schema
  (doseq [specification (bundle/load-all)]
    (testing (str (:spec/id specification))
      (let [reference (identity/specification-ref specification)]
        ;; `SpecificationRef` is `{:closed true}`, so this now catches a fourth
        ;; identity field on its own — an open map would have accepted one that
        ;; the schema knew nothing about.
        (is (m/validate schema/SpecificationRef reference))
        ;; Asserted as well as validated, because README section 29 says
        ;; identity is these three questions, and that claim should not depend
        ;; on remembering to keep one Malli option set.
        (is (= #{:id :version :hash} (set (keys reference))))))))

(deftest every-result-carries-the-same-reference
  (let [expected (identity/specification-ref specification)
        ;; One reference, three result shapes. README section 11 fixes the
        ;; precedence, so an invalid command needs a valid state to reach it.
        results {:decision
                 (:decision (decider/prepare-and-decide specification valid-state valid-command))

                 :invalid-command
                 (decider/prepare-and-decide specification
                                             valid-state
                                             (assoc-in valid-command [:data :quantity] "three"))

                 :invalid-state
                 (decider/prepare-and-decide specification
                                             (assoc valid-state :tickets-remaining "lots")
                                             valid-command)}]
    (doseq [[result-type result] results]
      (testing result-type
        (is (m/validate schema/SpecificationRef (:spec/ref result)))
        (is (= expected (:spec/ref result)))))
    (is (= :invalid-command (:result/type (:invalid-command results))))
    (is (= :invalid-state (:result/type (:invalid-state results))))))

(deftest hash-is-content-identity
  (testing "the prefix is part of the format — README section 31"
    (is (str/starts-with? (:spec/hash specification) "sha256:")))

  (testing "recomputing a loaded bundle reproduces its hash — README section 30"
    (is (= (:spec/hash specification)
           (hash/specification-hash specification))))

  (testing "map iteration order is not semantic — README section 31"
    (is (= (hash/specification-hash specification)
           (hash/specification-hash (into (sorted-map) specification)))))

  (testing "a documentary-only change still changes the hash — README section 32"
    (is (not= (hash/specification-hash specification)
              (hash/specification-hash
               (assoc-in specification [:rules 0 :rule/text] "Reworded."))))))
