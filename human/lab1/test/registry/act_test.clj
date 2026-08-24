(ns registry.act-test
  "Six tests, one per property. No generators, no Docker, no I/O.

   Two of them pass by construction and are gates on act types 2..N rather
   than checks on these two — they are marked below. A reader who sees six
   green tests and infers six proofs has been misled, so the file says which
   is which."
  (:require [clojure.test :refer [deftest testing is]]
            [registry.act :as act]))

;; ---------------------------------------------------------------------------
;; Informative today? No — a gate on act types 2..N.
;;
;; Both acts are hand-written literals containing all thirteen keys, so this
;; passes by construction. It earns its place on the day someone adds
;; :refusal-of-dealing and has not thought about Q11.
;; ---------------------------------------------------------------------------

(deftest every-act-answers-all-thirteen
  (testing "the registration resolves Q1–Q13"
    (is (empty? (act/unanswered act/registration-of-transfer))))

  (testing "so does the rectification"
    (is (empty? (act/unanswered act/rectification-of-register))))

  (testing "there are thirteen of them, and they are Q1–Q13"
    (is (= 13 (count act/questions)))
    (is (= (set (map #(keyword (str "q" %)) (range 1 14)))
           (set (keys act/questions)))))

  (testing "a candidate answering none of them names all thirteen"
    (is (= 13 (count (act/unanswered act/folio-row))))))

;; ---------------------------------------------------------------------------
;; Informative today? Yes — this pins `some?` over `contains?`.
;;
;; "Not applicable" is a valid answer where a regime genuinely does not apply.
;; "Unknown because we failed to capture it" never is. Implement `unanswered`
;; with `contains?` and the second one starts passing silently.
;; ---------------------------------------------------------------------------

(deftest not-applicable-is-an-answer-nil-is-not
  (testing "a rectification fixes no priority, and says so"
    (is (= :not-applicable (:act/priority act/rectification-of-register)))
    (is (not (contains? (act/unanswered act/rectification-of-register) :q11))))

  (testing "a key we failed to capture is unanswered even when it is present"
    (is (contains? (act/unanswered (assoc act/registration-of-transfer
                                          :act/priority nil))
                   :q11)))

  (testing "and so is one that is simply absent"
    (is (contains? (act/unanswered (dissoc act/registration-of-transfer
                                           :act/priority))
                   :q11))))

;; ---------------------------------------------------------------------------
;; Informative today? Weakly — the predicate is the artefact, not the result.
;;
;; `act?` is the thing to point at a list of forty candidate event types when
;; a workshop produces one, rather than adjudicating each by argument.
;; ---------------------------------------------------------------------------

(deftest the-non-acts-fail-q6
  (testing "one candidate per store, none of them an act"
    (is (= [:assertion-ledger :audit :operational]
           (sort (keys act/not-acts))))
    (is (every? (complement act/act?) (vals act/not-acts))))

  (testing "nor is what CRUD would store"
    (is (not (act/act? act/folio-row))))

  (testing "nor is a field change — no power to change a name in isolation"
    (is (not (act/act? act/field-change))))

  (testing "both acts pass"
    (is (act/act? act/registration-of-transfer))
    (is (act/act? act/rectification-of-register))
    (is (every? act/act? act/acts))))

;; ---------------------------------------------------------------------------
;; Informative today? Yes — fails on the first merge.
;;
;; Every key of an act is in the `act` namespace, so an envelope key cannot be
;; inside one without this noticing. Lab 6 depends on it: the store must be
;; able to add :stream/version without changing `evolve`'s input type.
;; ---------------------------------------------------------------------------

(deftest the-act-carries-no-envelope-keys
  (testing "an act is entirely :act/*"
    (doseq [a act/acts]
      (is (every? #(= "act" (namespace %)) (keys a)))))

  (testing "the envelope wraps the act; it does not merge with it"
    (is (= #{:envelope :act} (set (keys act/registration-in-a-stream))))
    (is (= act/registration-of-transfer (:act act/registration-in-a-stream))))

  (testing "and the envelope holds only machinery"
    (is (= #{"stream" "schema" "correlation"}
           (set (map namespace (keys (:envelope act/registration-in-a-stream))))))))

;; ---------------------------------------------------------------------------
;; Informative today? Yes — fails if either axis is dropped.
;;
;; C2. Lab 3's `as-at` has two cut-offs to filter on only because these are
;; two separate keys here.
;; ---------------------------------------------------------------------------

(deftest two-time-axes-come-apart
  (testing "the registration was recorded seconds after it took effect"
    (is (pos? (compare (:act/recorded-at act/registration-of-transfer)
                       (:act/effective-at act/registration-of-transfer)))))

  (testing "the rectification takes effect seven years before it was recorded"
    (is (neg? (compare (:act/effective-at act/rectification-of-register)
                       (:act/recorded-at act/rectification-of-register)))))

  (testing "it takes effect exactly when the act it corrects did"
    (is (= (:act/effective-at act/registration-of-transfer)
           (:act/effective-at act/rectification-of-register))))

  (testing "which is what separates 'we were wrong' from 'the title changed'"
    (is (= :rectification-of-register (:act/type act/rectification-of-register)))
    (is (= :clerical-error (get-in act/rectification-of-register
                                   [:act/decision :ground])))))

;; ---------------------------------------------------------------------------
;; Informative today? No — a property of Clojure, kept as documentation.
;;
;; An act does not change because we forbade editing it. A value is not a
;; place, so there is nothing to overwrite.
;; ---------------------------------------------------------------------------

(deftest an-act-is-a-value
  (let [amended (assoc-in act/registration-of-transfer
                          [:act/decision :class-of-title] :qualified)]
    (testing "amending returns a new act"
      (is (= :qualified (get-in amended [:act/decision :class-of-title]))))

    (testing "and leaves the original untouched"
      (is (= :absolute (get-in act/registration-of-transfer
                               [:act/decision :class-of-title]))))

    (testing "so a mistake becomes a registration AND a rectification"
      (is (= 2 (count act/acts))))))
