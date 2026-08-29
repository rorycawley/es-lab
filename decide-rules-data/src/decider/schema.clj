(ns decider.schema
  "Validate the language and the structure of a semantic bundle.

   Two jobs that look alike but are not. The Malli schemas below say what shape
   a bundle has. `problems` says what is *wrong* with a particular bundle, in
   enough detail for whoever wrote it to fix it — Malli's `[:fn ...]`
   predicates can only answer yes or no, so every check worth explaining is
   also written out longhand here.

   The language accepted here and the language executed by `decider.dsl` must
   stay synchronized. `operand-counts` is the one place that lists the
   operators, and `decider.validation-test` checks it against the interpreter.

   The public surface is deliberately small, because everything public is
   something not to break:

     problems, assert-valid-bundle!    ask whether a bundle is executable
     SemanticBundle                    the shape of a bundle
     Result, Decision,                 the shapes `decider.core/decide`
       InvalidInput, SpecificationRef    returns
     sources, operand-counts,          what the language and its structures
       rule-keys, max-depth              admit

   Everything else — the `[:fn ...]` predicates, the per-check problem
   functions — is machinery. Use `problems`: it runs all of it and says more."
  (:require
   [clojure.set :as set]
   [decider.dsl :as dsl]
   [malli.core :as m]
   [malli.error :as me]))

(def sources
  "Where an `:expr/get` may read from — README section 21."
  #{:state :command :derived})

(def operand-counts
  "How many operands each DSL operator takes.

   This is the list of operators the language has. `decider.dsl` must implement
   exactly these and no others; a bundle using anything else is rejected before
   it can run."
  {:expr/get       2
   :expr/get-or    3
   :expr/=         2
   :expr/not=      2
   :expr/<=        2
   :expr/+         2
   :expr/contains? 2
   :expr/member?   2
   :expr/nil?      1
   :expr/not       1
   :expr/values    1
   :expr/if        3})

(def max-depth
  "How deeply a semantic bundle may nest before it is rejected.

   Every walk over a bundle in this project — canonicalization, validation,
   template rendering — is naively recursive, so bundle depth is stack depth.
   A bundle nested past this limit is refused rather than allowed to overflow
   the stack somewhere less obvious. Real bundles nest around ten levels."
  100)

(def max-nodes
  "How many values a semantic bundle may contain before it is rejected.

   Depth alone does not bound a bundle: a single event template with two hundred
   thousand keys nests three levels and validates in moments that add up. Real
   bundles here run to about 280 values, so this leaves several orders of
   magnitude of headroom while still refusing something built to exhaust the
   machine that reads it."
  100000)

(defn- deeper-than?
  "True when `x` nests collections more than `limit` levels deep.

   Iterative on purpose: a recursive depth check would overflow on exactly the
   input it exists to reject."
  [limit x]
  (loop [level [x]
         depth 0]
    (cond
      (empty? level) false
      (> depth limit) true
      :else
      (recur (into []
                   (mapcat (fn [node]
                             (cond
                               (map? node)  (concat (keys node) (vals node))
                               (coll? node) (seq node)
                               :else        nil)))
                   level)
             (inc depth)))))

(defn- larger-than?
  "True when `x` contains more than `limit` values, counting keys, entries and
   scalars. Iterative, and stops counting as soon as the answer is known."
  [limit x]
  (loop [stack (list x)
         seen  0]
    (cond
      (> seen limit)  true
      (empty? stack)  false
      :else
      (let [node      (peek stack)
            remaining (pop stack)]
        (recur (cond
                 (map? node)  (into remaining (concat (keys node) (vals node)))
                 (coll? node) (into remaining node)
                 :else        remaining)
               (inc seen))))))

(defn- unwalkable
  "Why `x` cannot safely be walked at all, or nil.

   Both checks run before anything else in `problems`, because everything else —
   canonicalization, validation, template rendering — walks the bundle
   recursively and would rather overflow or grind than report."
  [x]
  (cond
    (deeper-than? max-depth x)
    {:problem :specification-too-deep :max-depth max-depth}

    (larger-than? max-nodes x)
    {:problem :specification-too-large :max-nodes max-nodes}))

(declare form-problem)

(defn- expression-problem
  "nil when `form` is a well-formed `:expr/*` expression, otherwise a map
   saying what is wrong with it.

   The map is plain data — operator, expected arity, offending form — so it can
   be logged, serialized, and read by whoever wrote the bundle. That is the
   whole reason this returns a reason instead of a boolean."
  [form]
  (if-not (dsl/expression? form)
    {:problem :not-an-expression
     :form    form}
    (let [[operator & operands] form
          expected (operand-counts operator)]
      (cond
        (nil? expected)
        {:problem  :unknown-operator
         :operator operator
         :known    (set (keys operand-counts))
         :form     form}

        (not= expected (count operands))
        {:problem  :wrong-operand-count
         :operator operator
         :expected expected
         :actual   (count operands)
         :form     form}

        (#{:expr/get :expr/get-or} operator)
        (let [[source path] operands]
          (or (when-not (contains? sources source)
                {:problem  :unknown-source
                 :operator operator
                 :source   source
                 :known    sources
                 :form     form})
              (when-not (vector? path)
                {:problem  :path-must-be-a-vector
                 :operator operator
                 :path     path
                 :form     form})
              ;; A derived value is looked up by name, and the name has to be
              ;; readable without running anything — that is what lets
              ;; `derived-references` check the reference graph statically.
              (when (and (= :derived source)
                         (not (keyword? (first path))))
                {:problem  :derived-path-must-start-with-a-keyword
                 :operator operator
                 :path     path
                 :form     form})
              (some form-problem path)
              (when (= :expr/get-or operator)
                (form-problem (nth operands 2)))))

        :else
        (some form-problem operands)))))

(defn- expression-anywhere?
  "Whether `x` is, or contains at any depth, something shaped like an
   expression."
  [x]
  (cond
    (dsl/expression? x) true
    (map? x)            (boolean (or (some expression-anywhere? (keys x))
                                     (some expression-anywhere? (vals x))))
    (coll? x)           (boolean (some expression-anywhere? x))
    :else               false))

(defn- form-problem
  "nil when `x` contains no malformed `:expr/*` expression, otherwise the first
   problem found. Ordinary data that is not trying to be an expression is fine
   and reports nothing."
  [x]
  (cond
    (dsl/expression? x)
    (expression-problem x)

    (map? x)
    ;; Keys first, and by a different rule. `decider.dsl/template-value` renders
    ;; a map's values and copies its keys through untouched, so an expression in
    ;; key position is not evaluated — it lands in the event as the literal
    ;; vector `[:expr/get :state [:k]]`. Nothing about that is detectable at
    ;; runtime and nothing about it is what the author meant, so it is refused
    ;; here rather than silently produced. README section 28.
    (or (some (fn [k]
                (when (expression-anywhere? k)
                  {:problem :expression-in-template-key
                   :key     k}))
              (keys x))
        (some form-problem (vals x)))

    (coll? x)
    (some form-problem x)

    :else
    nil))

(defn- valid-expression?
  "Whether `form` is a well-formed `:expr/*` expression. See
   `expression-problem` for why it is not."
  [form]
  (nil? (expression-problem form)))

(defn- valid-form?
  "Whether `x` is free of malformed `:expr/*` expressions. See `form-problem`."
  [x]
  (nil? (form-problem x)))

(defn- valid-malli-schema?
  "Whether `schema` compiles as a Malli schema. `problems` reports the compiler
   message; this only answers yes or no, because that is all Malli's `[:fn ...]`
   can carry."
  [schema]
  (try
    (m/schema schema)
    true
    (catch Exception _
      false)))

(def ^:private Expression
  "A single executable `:expr/*` form."
  [:fn
   {:error/message "must be a valid :expr/* expression"}
   valid-expression?])

(def ^:private Template
  "Arbitrary data that may contain `:expr/*` forms — an event template, or the
   right-hand side of a derivation."
  [:fn
   {:error/message "contains an invalid :expr/* expression"}
   valid-form?])

(def ^:private EventTemplate
  "A map template, so rendering it always produces the map `Decision` promises
   for each event."
  [:and
   [:fn {:error/message "must be a map"} map?]
   Template])

(def ^:private MalliSchema
  "A Malli schema stored as data inside a bundle."
  [:fn
   {:error/message "must be a valid Malli schema"}
   valid-malli-schema?])

;; ---------------------------------------------------------------------------
;; Every map below is `{:closed true}`, and that is a deliberate difference from
;; the schemas a bundle carries in `:state/schema` and `:command/schema`.
;;
;; Those describe input arriving from a caller, and README section 17 keeps them
;; open on purpose: what a domain accepts is the bundle author's decision.
;;
;; These describe structures this project itself writes — the shape of a bundle,
;; the shape of a result. An unrecognised key here is not extensibility, it is a
;; typo, and an open map turns it into a silent one. `:rule/aftr` validated
;; cleanly while the guard it was meant to declare simply did not exist, which
;; is the exact failure `:rule/after` was added to prevent.
;; ---------------------------------------------------------------------------

;; The shape built by `decider.identity/specification-ref`. Two definitions of
;; one shape, so they must move together -- closing this is what makes
;; `m/validate` notice, rather than only `decider.identity-test`.
(def SpecificationRef
  "Which specification produced a result — README section 29."
  [:map {:closed true}
   [:id [:fn qualified-keyword?]]
   [:version [:int {:min 1}]]
   [:hash :string]])

(def Decision
  "The business answer to a request the domain understood."
  [:or
   [:map {:closed true}
    [:decision/type [:enum :rejected]]
    [:spec/ref SpecificationRef]
    [:rule/id :keyword]
    [:reason :keyword]]

   [:map {:closed true}
    [:decision/type [:enum :accepted]]
    [:spec/ref SpecificationRef]
    [:events [:vector [:fn map?]]]]])

(def InvalidInput
  "A request the domain could not understand — README section 10.

   `:errors` is `malli.error/humanize` output, whose shape mirrors whichever
   schema rejected the value, so it is constrained only to be present. Callers
   should treat it as a diagnostic to show a human, not as a value to branch
   on; `:result/type` is what to branch on."
  [:map {:closed true}
   [:result/type [:enum :invalid-state :invalid-command]]
   [:spec/ref SpecificationRef]
   [:errors [:fn {:error/message "must carry the validation errors"} some?]]])

(def Result
  "Everything `decider.core/decide` can return.

   It cannot describe the fourth outcome, which is a thrown exception — see
   `decider.core/decide`."
  [:or
   [:map {:closed true}
    [:result/type [:enum :decision]]
    [:decision Decision]]
   InvalidInput])

(def SemanticBundle
  "The executable definition of one domain decision — README section 13."
  [:map {:closed true}
   [:spec/id [:fn qualified-keyword?]]
   [:spec/version [:int {:min 1}]]

   ;; Not authored in the EDN — `decider.bundle/load` and
   ;; `decider.core/prepare` attach it, and both validate a bundle that may
   ;; already carry one. Optional here so a loaded bundle stays valid, and
   ;; declared here because the map is closed.
   [:spec/hash {:optional true} :string]

   [:rule-evaluation
    [:map {:closed true}
     [:strategy [:enum :first-failure]]]]

   [:state/schema MalliSchema]
   [:command/schema MalliSchema]

   [:derive
    [:vector
     [:tuple :keyword Template]]]

   [:rules
    [:vector {:min 1}
     [:map {:closed true}
      [:rule/id :keyword]
      [:rule/text :string]
      ;; Optional, and load bearing where it appears: see
      ;; `rule-order-problems` and README section 16. The closed map above is
      ;; what stops `:rule/aftr` quietly meaning nothing.
      [:rule/after {:optional true} [:vector :keyword]]
      [:require Expression]
      [:otherwise :keyword]]]]

   [:events
    [:vector {:min 1} EventTemplate]]])

(defn- duplicates
  [xs]
  (->> xs
       frequencies
       (keep (fn [[x n]]
               (when (< 1 n) x)))
       set))

(defn- derived-references
  "Every derived name that `x` reads, at any depth."
  [x]
  (cond
    (dsl/expression? x)
    (let [[operator source path & _] x
          own-ref (when (and (#{:expr/get :expr/get-or} operator)
                             (= :derived source)
                             (vector? path)
                             (keyword? (first path)))
                    #{(first path)})]
      (into (or own-ref #{})
            (mapcat derived-references)
            (rest x)))

    (map? x)
    (into #{} (mapcat derived-references) (vals x))

    (coll? x)
    (into #{} (mapcat derived-references) x)

    :else
    #{}))

(defn- derivation-reference-problems
  "Derivations execute in order, so a derivation may only read names bound
   before it — README section 25."
  [bindings]
  (loop [known #{}
         [[name form] & remaining] bindings
         problems []]
    (if-not name
      problems
      (let [missing (set/difference (derived-references form) known)]
        (recur (conj known name)
               remaining
               (cond-> problems
                 (seq missing)
                 (conj {:problem      :undefined-derived-reference
                        :derived/name name
                        :missing      missing})))))))

(defn- rule-order-problems
  "A rule that declares `:rule/after` may only be evaluated once those rules
   have passed, so they must appear before it — README section 16.

   This is what makes a guard explicit. Under `:first-failure` a rule like
   \"the product must exist\" is an implicit precondition of every later rule
   that reads the product, and moving it later turns a clean rejection into a
   wrong answer or a crash. Declaring the dependency lets that reordering be
   caught here instead of in production."
  [rules]
  (let [position (into {} (map-indexed (fn [i rule] [(:rule/id rule) i])) rules)]
    (vec
     (for [[i rule] (map-indexed vector rules)
           ;; Malli reports a malformed :rule/after. This detail check should
           ;; only inspect the vector shape it understands, never throw while
           ;; trying to improve that report.
           guard    (if (vector? (:rule/after rule))
                      (:rule/after rule)
                      [])
           :let     [guard-position (position guard)]
           :when    (or (nil? guard-position) (<= i guard-position))]
       (if (nil? guard-position)
         {:problem    :unknown-guard-rule
          :rule/id    (:rule/id rule)
          :rule/after guard}
         {:problem    :guard-rule-out-of-order
          :rule/id    (:rule/id rule)
          :rule/after guard})))))

(def rule-keys
  "Every key a rule may carry. The closed `:map` in `SemanticBundle` enforces
   this; `unknown-rule-key-problems` is what says which rule."
  #{:rule/id :rule/text :rule/after :require :otherwise})

(defn- unknown-rule-key-problems
  "Keys on a rule that are not `rule-keys`.

   Malli's closed-map error reports these positionally — `[#:rule{:aftr [...]}]`
   leaves you counting entries to find the rule. Since the typo this exists to
   catch is `:rule/aftr` for `:rule/after`, and its consequence is a guard that
   silently does not exist, it is worth naming the rule."
  [rules]
  (vec
   (for [rule  rules
         :let  [unknown (set/difference (set (keys rule)) rule-keys)]
         :when (seq unknown)]
     {:problem :unknown-rule-key
      :rule/id (:rule/id rule)
      :keys    unknown
      :known   rule-keys})))

(defn- malli-schema-problems
  "Why an embedded schema failed to compile, rather than merely that it did.

   Malli reports a compile failure as `:malli.core/invalid-schema` with the
   offending sub-form in `ex-data`, so the message alone says nothing useful —
   which is what made `MalliSchema` returning a bare false so unhelpful."
  [specification]
  (vec
   (keep (fn [k]
           (try
             (m/schema (get specification k))
             nil
             (catch Exception cause
               (let [data (ex-data cause)]
                 (cond-> {:problem    :invalid-malli-schema
                          :schema/key k
                          :reason     (or (:type data) :malli/error)
                          :message    (ex-message cause)}
                   ;; Rendered, not embedded: Malli's error data can hold
                   ;; compiled schema objects, and a problem report has to stay
                   ;; printable and readable back as data.
                   (contains? (:data data) :form)
                   (assoc :offending-form
                          (pr-str (get-in data [:data :form]))))))))
         [:state/schema :command/schema])))

;; ---------------------------------------------------------------------------
;; The detail checks below read a bundle's parts as the shapes they are supposed
;; to be. A bundle that is wrong enough not to have those shapes — `:derive` a
;; string, `:events` a number — is already reported by the Malli shape check,
;; and the detail walks would only destructure their way into an
;; IllegalArgumentException on the way to saying nothing new.
;;
;; So each part is narrowed to the entries that can be inspected, and the rest
;; is left to `:invalid-semantic-bundle`. `problems` reports; it does not throw.
;; ---------------------------------------------------------------------------

(defn- inspectable-bindings
  [specification]
  (let [derive (:derive specification)]
    (if (sequential? derive)
      (filterv #(and (sequential? %) (= 2 (count %))) derive)
      [])))

(defn- inspectable-rules
  [specification]
  (let [rules (:rules specification)]
    (if (sequential? rules)
      (filterv map? rules)
      [])))

(defn- inspectable-events
  [specification]
  (let [events (:events specification)]
    (if (sequential? events)
      (vec events)
      [])))

(defn- expression-problems
  "Malformed expressions, each tagged with where in the bundle it sits."
  [specification]
  (vec
   (concat
    (for [[name form] (inspectable-bindings specification)
          :let        [problem (form-problem form)]
          :when       problem]
      (assoc problem :in [:derive name]))
    (for [rule  (inspectable-rules specification)
          :let  [problem (form-problem (:require rule))]
          :when problem]
      (assoc problem :in [:rules (:rule/id rule)]))
    (for [[i event] (map-indexed vector (inspectable-events specification))
          :let      [problem (form-problem event)]
          :when     problem]
      (assoc problem :in [:events i])))))

(defn problems
  "Everything wrong with `specification`, as a vector of plain data maps. Empty
   means the bundle is executable.

   Each map has a `:problem` key naming the kind, and whatever else identifies
   it — the rule id, the offending form, the compiler message. Nothing here
   holds a Malli schema object or an exception, so the result can be logged and
   serialized as it is."
  [specification]
  (if-let [refusal (unwalkable specification)]
    [refusal]
    (let [shape-valid?        (m/validate SemanticBundle specification)
          bindings            (inspectable-bindings specification)
          rules               (inspectable-rules specification)
          events              (inspectable-events specification)
          rule-ids            (map :rule/id rules)
          derived             (map first bindings)
          duplicate-rule-ids  (duplicates rule-ids)
          duplicate-derived   (duplicates derived)
          derivation-problems (derivation-reference-problems bindings)
          all-derived         (set derived)
          rule-event-refs     (derived-references [rules events])
          missing-rule-event-refs (set/difference rule-event-refs all-derived)]
      (cond-> []
        (not shape-valid?)
        (conj {:problem :invalid-semantic-bundle
               :errors  (me/humanize (m/explain SemanticBundle specification))})

        :always
        (into (malli-schema-problems specification))

        :always
        (into (expression-problems specification))

        :always
        (into (rule-order-problems rules))

        :always
        (into (unknown-rule-key-problems rules))

        (seq duplicate-rule-ids)
        (conj {:problem  :duplicate-rule-ids
               :rule/ids duplicate-rule-ids})

        (seq duplicate-derived)
        (conj {:problem       :duplicate-derived-names
               :derived/names duplicate-derived})

        (seq derivation-problems)
        (into derivation-problems)

        (seq missing-rule-event-refs)
        (conj {:problem :undefined-derived-reference
               :missing missing-rule-event-refs})))))

(defn assert-valid-bundle!
  "Return `specification` if it is executable, otherwise throw.

   A broken bundle is a defect in the software or the specification, not a
   business outcome, so it is never turned into a decision — README
   section 11."
  [specification]
  (when-let [problems (seq (problems specification))]
    (throw
     (ex-info "Invalid semantic bundle"
              {:problems     problems
               :spec/id      (:spec/id specification)
               :spec/version (:spec/version specification)})))
  specification)
