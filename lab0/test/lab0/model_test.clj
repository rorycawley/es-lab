(ns lab0.model-test
  "The reduction, as a criterion rather than a slogan.

  \"A model leaves things out\" is easy to agree with and useless on its own,
  because it says nothing about *which* things. This namespace uses a test you
  can actually apply to a candidate attribute:

      An attribute that cannot change any answer is not part of the model.

  Which is checkable. Vary the attribute across every value the business might
  give it, ask every question the model exists to answer, and see whether any
  answer moves. If none does, the attribute is real, is true, and belongs
  somewhere else."
  (:require [clojure.test :refer [deftest is testing]]
            [lab0.truck :as truck]))

;; ---------------------------------------------------------------------------
;; Everything true about truck IC-2019-A, one morning in August
;; ---------------------------------------------------------------------------

(def the-whole-truck
  "None of this is invented and none of it is wrong. The fleet manager could
  tell you all of it, and would be annoyed if you lost any of it."
  {:stock                 {"vanilla" 3 "chocolate" 2}
   :registration          "IC-2019-A"
   :paint-colour          "pink"
   :chime-tune            "Greensleeves"
   :tyre-pressure-psi     32
   :odometer-km           84213
   :insurance-renews      #inst "2027-03-01"
   :last-washed           #inst "2026-08-18"
   :freezer-serial        "FZ-88120-B"
   :driver                {:name "Dana" :favourite-radio-station "Lyric FM"}})

(def the-model
  "The same truck, reduced to what any question below actually consults."
  {:stock {"vanilla" 3 "chocolate" 2}})

;; Values the business might plausibly give each attribute — including the
;; ones that sound like they ought to matter.
(def variations
  {:registration      ["IC-2019-A" "IC-2024-Z" ""]
   :paint-colour      ["pink" "white" "unpainted"]
   :chime-tune        ["Greensleeves" "O Sole Mio" nil]
   :tyre-pressure-psi [32 18 0]
   :odometer-km       [84213 0 999999]
   :insurance-renews  [#inst "2027-03-01" #inst "1999-01-01"]
   :last-washed       [#inst "2026-08-18" nil]
   :freezer-serial    ["FZ-88120-B" "FZ-00001-A"]
   :driver            [{:name "Dana"} {:name "Sam"} nil]})

(defn- every-answer
  "Every question this model exists to answer, for one truck."
  [t]
  {:sellable-vanilla   (truck/sellable? t "vanilla")
   :sellable-pistachio (truck/sellable? t "pistachio")
   :stock-of-vanilla   (truck/stock-of t "vanilla")
   :total-stock        (truck/total-stock t)
   :room-for-10        (truck/room-for? t 10)
   :room-for-100       (truck/room-for? t 100)})

;; ---------------------------------------------------------------------------
;; The criterion, both ways round
;; ---------------------------------------------------------------------------

(deftest an-attribute-that-cannot-change-an-answer-is-not-in-the-model-test
  (let [baseline (every-answer the-whole-truck)]
    (doseq [[attribute values] variations
            value values]
      (is (= baseline (every-answer (assoc the-whole-truck attribute value)))
          (str "changing " attribute " to " (pr-str value)
               " moved an answer — then it belongs in the model")))))

(deftest an-attribute-that-can-change-an-answer-is-the-model-test
  (testing "and :stock earns its place by failing the same test"
    (is (not= (every-answer the-whole-truck)
              (every-answer (assoc the-whole-truck :stock {}))))
    (is (true?  (truck/sellable? {:stock {"vanilla" 1}} "vanilla")))
    (is (false? (truck/sellable? {:stock {"vanilla" 0}} "vanilla")))))

(deftest the-reduction-answers-identically-to-the-whole-thing-test
  (testing "nine attributes removed, and not one answer changed"
    (is (= (every-answer the-whole-truck) (every-answer the-model)))
    (is (= 1 (count (keys the-model))))
    (is (= 10 (count (keys the-whole-truck))))))

(deftest what-is-left-out-is-not-lost-test
  (testing "it is somewhere else, and the model is not the system"
    ;; A reduction is not a claim that the rest does not exist. The fleet
    ;; manager still needs the insurance renewal date; it is simply not part of
    ;; deciding whether a cone can be sold. Different question, different
    ;; model — and forcing both into one is how a model stops being useful.
    (is (contains? the-whole-truck :insurance-renews))
    (is (not (contains? the-model :insurance-renews)))))

;; ---------------------------------------------------------------------------
;; The invariants themselves
;; ---------------------------------------------------------------------------

(deftest you-cannot-sell-what-you-have-not-got-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Sold out"
                        (truck/sell truck/empty-truck "vanilla")))
  (is (= {:stock {"vanilla" 0}} (truck/sell {:stock {"vanilla" 1}} "vanilla"))))

(deftest you-cannot-load-more-than-the-truck-holds-test
  (is (= 40 truck/capacity))
  (is (true?  (truck/room-for? truck/empty-truck 40)))
  (is (false? (truck/room-for? truck/empty-truck 41)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No room"
                        (truck/load-cones {:stock {"vanilla" 39}} "chocolate" 2))))

(deftest a-rule-can-be-asked-on-its-own-test
  (testing "which is what having a name buys you"
    ;; No truck was constructed, nothing was saved, and the question was asked
    ;; of the rule directly. `models/truck.clj` cannot do this, and the
    ;; contrast test says why.
    (is (false? (truck/room-for? {:stock {"vanilla" 40}} 1)))
    (is (true?  (truck/sellable? {:stock {"vanilla" 1}} "vanilla")))))

;; ---------------------------------------------------------------------------
;; The change the business asked for in August
;; ---------------------------------------------------------------------------

(deftest a-business-rule-and-its-code-are-the-same-size-test
  (testing "'the truck holds forty cones' arrived as one sentence"
    ;; It cost one constant and one predicate, in the file the rule is about,
    ;; and this test needed no setup to check it. That is the advantage the
    ;; whole lab is arguing for: a change in the business produces a change in
    ;; the code of the same nature and scale.
    ;;
    ;; The same sentence, said to `models/truck.clj`, lands inside a method
    ;; between a read and a write, next to a timestamp.
    (is (= 40 truck/capacity))
    (is (false? (truck/room-for? {:stock {"vanilla" 40}} 1)))
    (testing "and raising it is a one-character change with no migration"
      (with-redefs [truck/capacity 50]
        (is (true? (truck/room-for? {:stock {"vanilla" 40}} 1)))))))
