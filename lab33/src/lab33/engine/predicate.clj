(ns lab33.engine.predicate
  "The other tempting version: a rule expressed as data.

      [:and [:> :amount 10000M] [:= :direction \"debit\"]]

  Twenty-five lines and it works. That is exactly the problem — it works well
  enough to ship, and everything it costs arrives later.

  What has just been built is an interpreter, which makes the configuration a
  programming language. It has no type checker, so `:ammount` is a valid
  program. It has no tests, because it lives in a file the test suite does not
  import. It has no code review, because changing it is 'a config change'. It
  has no `git blame` if it lives in a database, which is where it ends up once
  somebody asks to change a rule without a deploy.

  And its worst failure is the quiet one. A misspelled field does not throw:
  `(get fact :ammount)` is `nil`, the comparison is false, the rule matches
  nothing, and the compliance report comes back empty — which looks exactly
  like a month with no reportable transactions. `predicate_test.clj` asserts
  that this is what happens, because a failure mode you can demonstrate is
  easier to refuse than one you have to imagine.

  `rules.clj` is what keeps this out: every declared parameter is a scalar
  predicate, so a vector like the one above cannot be the value of any
  configurable key."
  (:require [clojure.string :as str]))

(declare evaluate)

(defn- compare-to
  "Numeric comparison that yields false rather than throwing on a missing
  field.

  This is the naive-and-realistic choice, and it is the bug. Throwing would
  turn a typo into a loud failure at start-up; returning false turns it into a
  rule that silently never fires. Anybody writing this in an afternoon writes
  the second one."
  [f fact field value]
  (let [found (get fact field)]
    (boolean (and (number? found) (number? value) (f (compare found value))))))

(defn evaluate
  "Is `fact` matched by `rule`?"
  [rule fact]
  (if-not (vector? rule)
    (throw (ex-info "Malformed rule" {:reason :malformed-rule :rule rule}))
    (let [[op & args] rule]
      (case op
        :and (every? #(evaluate % fact) args)
        :or  (boolean (some #(evaluate % fact) args))
        :not (not (evaluate (first args) fact))
        :>   (compare-to pos? fact (first args) (second args))
        :<   (compare-to neg? fact (first args) (second args))
        :=   (= (get fact (first args)) (second args))
        :in  (boolean ((set (second args)) (get fact (first args))))
        (throw (ex-info "Unknown operator" {:reason :unknown-operator :operator op}))))))

(defn explain
  "The rule as something close to English, which is the feature people ask for
  and the reason this design keeps getting proposed."
  [rule]
  (let [[op & args] rule]
    (case op
      :and (str "(" (str/join " and " (map explain args)) ")")
      :or  (str "(" (str/join " or " (map explain args)) ")")
      :not (str "not " (explain (first args)))
      :>   (str (name (first args)) " > " (second args))
      :<   (str (name (first args)) " < " (second args))
      :=   (str (name (first args)) " = " (pr-str (second args)))
      :in  (str (name (first args)) " in " (pr-str (second args)))
      (str "?" op))))
