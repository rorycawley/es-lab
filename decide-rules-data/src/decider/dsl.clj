(ns decider.dsl
  "Pure computational semantics of the EDN decision language.

   EDN does not execute itself. This namespace is what a `:expr/*` form means —
   nothing more. It must stay domain-blind and effect-free: no clock, no
   randomness, no I/O, and no knowledge of tickets, seats or auctions.

   It assumes its inputs have already been validated. `decider.core` is what
   guarantees that."
  (:require
   [decider.identity :as identity]))

(defn expression?
  "Whether `x` is trying to be an executable expression: a vector whose first
   element is a keyword in the `expr` namespace.

   Deliberately structural rather than semantic. `[:economy :business]` is
   ordinary data; `[:expr/unknown 1 2]` is a broken expression rather than a
   two-element vector, and saying so is what lets the validator reject it."
  [x]
  (and (vector? x)
       (keyword? (first x))
       (= "expr" (namespace (first x)))))

(declare expression-value)

(defn- path-value
  [environment path]
  (mapv #(expression-value environment %) path))

(defn expression-value
  "Evaluate `form` against `environment`, a map of `:state`, `:command` and
   `:derived`.

   Anything that is not an expression evaluates to itself, so literals may be
   written directly. Throws for an unknown operator: the validator rejects
   those already, and this is the second of the two checks README section 20
   keeps deliberately."
  [environment form]
  (if-not (expression? form)
    form
    (let [[operator & operands] form]
      (case operator
        :expr/get
        (let [[source path] operands]
          (get-in (get environment source)
                  (path-value environment path)))

        :expr/get-or
        (let [[source path default] operands]
          (get-in (get environment source)
                  (path-value environment path)
                  (expression-value environment default)))

        :expr/=
        (= (expression-value environment (first operands))
           (expression-value environment (second operands)))

        :expr/not=
        (not= (expression-value environment (first operands))
              (expression-value environment (second operands)))

        :expr/<=
        (<= (expression-value environment (first operands))
            (expression-value environment (second operands)))

        :expr/+
        (+ (expression-value environment (first operands))
           (expression-value environment (second operands)))

        :expr/nil?
        (nil? (expression-value environment (first operands)))

        :expr/not
        (not (expression-value environment (first operands)))

        :expr/contains?
        (contains? (expression-value environment (first operands))
                   (expression-value environment (second operands)))

        ;; `vec`, so that every collection the interpreter produces is a
        ;; vector. Bare `vals` yields a seq, which prints as `(a b)` and
        ;; round-trips through EDN as a list — a distinction `decider.hash`
        ;; treats as real and no bundle author intended.
        :expr/values
        (vec (vals (expression-value environment (first operands))))

        :expr/member?
        (let [[collection-form value-form] operands
              collection (expression-value environment collection-form)
              value      (expression-value environment value-form)]
          (boolean (some #(= value %) collection)))

        :expr/if
        (let [[condition then else] operands]
          (if (expression-value environment condition)
            (expression-value environment then)
            (expression-value environment else)))

        (throw
         (ex-info "Unknown expression operator"
                  {:operator operator
                   :form form}))))))

(defn template-value
  "Recursively render `x`, evaluating any `:expr/*` forms inside it and leaving
   everything else as data. This is how an accepted decision's events are
   built — README section 28."
  [environment x]
  (cond
    (expression? x)
    (expression-value environment x)

    (map? x)
    (into {}
          (map (fn [[k v]]
                 [k (template-value environment v)]))
          x)

    (vector? x)
    (mapv #(template-value environment %) x)

    (set? x)
    (into #{} (map #(template-value environment %)) x)

    (seq? x)
    (doall (map #(template-value environment %) x))

    :else
    x))

(defn- failed
  "Rethrow an interpreter failure as something diagnosable.

   The DSL is untyped by design (README section 23), so a bundle can ask for
   `(+ nil 1)` and Clojure will raise a `NullPointerException` from inside
   `clojure.core/+` — an exception that names neither the rule nor the
   expression that caused it. This wraps it in the context that does."
  [description context cause]
  (throw
   (ex-info (str "Specification failed while evaluating " description)
            (assoc context :evaluating description)
            cause)))

(defn- derived-environment
  "Bind each `:derive` name in order, so a later derivation can read an earlier
   one — README section 25.

   `context` is merged into the exception data if a binding blows up."
  ([environment bindings]
   (derived-environment environment bindings nil))
  ([environment bindings context]
   (reduce
    (fn [environment [name form]]
      (assoc-in environment
                [:derived name]
                (try
                  (template-value environment form)
                  (catch Exception cause
                    (failed (str "derived value " name)
                            (assoc context :derived/name name :form form)
                            cause)))))
    (assoc environment :derived {})
    bindings)))

(defn- first-failed-rule
  "The first rule whose `:require` is not satisfied, or nil if every rule
   passes. Order is normative — README section 16.

   `context` is merged into the exception data if a rule blows up."
  ([environment rules]
   (first-failed-rule environment rules nil))
  ([environment rules context]
   (some
    (fn [rule]
      (when-not (try
                  (expression-value environment (:require rule))
                  (catch Exception cause
                    (failed (str "rule " (:rule/id rule))
                            (assoc context :rule/id (:rule/id rule)
                                   :form (:require rule))
                            cause)))
        rule))
    rules)))

(defn decision
  "The business answer for `state` and `command` under `specification`.

   Assumes both have already been validated against the bundle's schemas and
   that the bundle itself is valid; `decider.core/decide` is the entry point
   that guarantees it. Throws if the specification asks for something the
   interpreter cannot do — see `failed`."
  [specification state command]
  (let [spec-ref    (identity/specification-ref specification)
        context     {:spec/ref spec-ref}
        environment (derived-environment
                     {:state state
                      :command command}
                     (:derive specification)
                     context)
        strategy    (get-in specification
                            [:rule-evaluation :strategy])]
    (case strategy
      :first-failure
      (if-let [rule (first-failed-rule environment
                                       (:rules specification)
                                       context)]
        {:decision/type :rejected
         :spec/ref      spec-ref
         :rule/id       (:rule/id rule)
         :reason        (:otherwise rule)}
        {:decision/type :accepted
         :spec/ref      spec-ref
         :events        (into []
                              (map-indexed
                               (fn [i event]
                                 (try
                                   (template-value environment event)
                                   (catch Exception cause
                                     (failed (str "event template " i)
                                             (assoc context :event/index i)
                                             cause)))))
                              (:events specification))})

      (throw
       (ex-info "Unsupported rule evaluation strategy"
                {:strategy strategy
                 :spec/ref spec-ref})))))
