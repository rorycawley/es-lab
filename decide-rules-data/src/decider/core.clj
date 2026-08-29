(ns decider.core
  "The public validated entry point.

   Two functions matter. `prepare` does everything that depends only on the
   specification — validate it, hash it, compile its schemas — and `decide`
   does the part that depends on the request. Splitting them is what keeps a
   decision cheap: a bundle is immutable, so hashing and validating it once per
   request is work that buys nothing."
  (:require
   [decider.dsl :as dsl]
   [decider.hash :as hash]
   [decider.identity :as identity]
   [decider.schema :as schema]
   [malli.core :as m]
   [malli.error :as me]))

(deftype ^:private PreparedSpecification
         [specification reference state-valid? state-errors command-valid? command-errors]
  clojure.lang.ILookup
  (valAt [this key]
    (.valAt this key nil))
  (valAt [_ key not-found]
    (case key
      :prepared/specification  specification
      :prepared/ref            reference
      :prepared/state-valid?   state-valid?
      :prepared/state-errors   state-errors
      :prepared/command-valid? command-valid?
      :prepared/command-errors command-errors
      not-found)))

(defn prepared?
  "Whether `x` is the result of `prepare` rather than a raw specification."
  [x]
  (instance? PreparedSpecification x))

(defn- compile-specification
  [specification]
  (schema/assert-valid-bundle! specification)
  (let [specification  (assoc specification
                              :spec/hash
                              (hash/specification-hash specification))
        state-schema   (:state/schema specification)
        command-schema (:command/schema specification)]
    (PreparedSpecification. specification
                            (identity/specification-ref specification)
                            (m/validator state-schema)
                            (m/explainer state-schema)
                            (m/validator command-schema)
                            (m/explainer command-schema))))

(defn prepare
  "Compile `specification` into the form `decide` uses.

   Validates the bundle, computes its content hash, and builds the Malli
   validators and explainers once. Throws if the bundle is not executable —
   README section 11.

   Validation happens before hashing, not after: `decider.hash/canonical`
   walks the bundle recursively, so a bundle too deeply nested to walk has to
   be refused before anything tries to walk it.

   Idempotent: preparing an already-prepared specification returns it. That is
   the useful asymmetry with `decide`, which refuses anything unprepared —
   strict where a mistake costs performance on every request, tolerant where
   calling twice costs nothing. It also lets a boundary call `prepare`
   defensively without knowing what it was handed.

   The result is an opaque, lookup-only value holding compiled functions under
   `:prepared/*` keys. It cannot be changed with `assoc`, because changing its
   specification independently of its cached hash and validators would make
   those values disagree. It is not EDN and should not be printed or
   serialized. `specification` gets the data back."
  [specification]
  (if (prepared? specification)
    specification
    (compile-specification specification)))

(defn specification
  "The specification inside a prepared specification, with `:spec/hash`
   attached."
  [prepared]
  (:prepared/specification prepared))

(defn- invalid
  [result-type prepared explainer value]
  {:result/type result-type
   :spec/ref    (:prepared/ref prepared)
   :errors      (me/humanize (explainer value))})

(defn decide
  "The business answer to `command` against `state`, under a **prepared**
   specification.

   Takes the output of `prepare`, not a raw bundle. That is deliberate: a raw
   bundle has to be re-hashed and re-validated before it can be used, which
   costs tens of times more than the decision itself (README section 43), and an
   entry point that quietly accepted either would make the expensive call the
   one that looks normal. `prepare-and-decide` is the one-shot version, named so
   the cost is visible at the call site.

   Returns one of three shapes, all described by `decider.schema/Result`:

     {:result/type :invalid-state   :spec/ref ... :errors ...}
     {:result/type :invalid-command :spec/ref ... :errors ...}
     {:result/type :decision        :decision ...}

   State is checked before command — README section 11.

   There is a fourth outcome: it throws. An invalid bundle throws from
   `prepare`, and a bundle that is well-formed but asks the interpreter for
   something impossible — comparing a value that is not there, adding to nil —
   throws `clojure.lang.ExceptionInfo` carrying `:spec/ref` and the rule or
   derivation that failed. Both are defects in the specification rather than
   business outcomes, so neither is turned into a decision. `:rule/after`
   exists to catch the common cause of the second before it ships — README
   section 16, reported by `decider.schema/problems`."
  ;; Not named `specification`: that would shadow this namespace's own
  ;; `specification`, and a map shadowing a function is the quiet kind of
  ;; mistake — `(specification x)` would return nil rather than fail.
  [prepared state command]
  ;; Without this, a raw bundle reaches `(:prepared/state-valid? prepared)`,
  ;; which is nil, and fails as `(nil state)` — a NullPointerException that
  ;; explains nothing.
  (when-not (prepared? prepared)
    (throw
     (ex-info (str "decide needs a prepared specification. "
                   "Call decider.core/prepare once and reuse the result, "
                   "or decider.core/prepare-and-decide for a single decision.")
              {:spec/id (:spec/id prepared)})))
  (cond
    (not ((:prepared/state-valid? prepared) state))
    (invalid :invalid-state
             prepared
             (:prepared/state-errors prepared)
             state)

    (not ((:prepared/command-valid? prepared) command))
    (invalid :invalid-command
             prepared
             (:prepared/command-errors prepared)
             command)

    :else
    {:result/type :decision
     :decision (dsl/decision (:prepared/specification prepared)
                             state
                             command)}))

(defn prepare-and-decide
  "Prepare `specification` and decide once with it.

   The convenience path, for a REPL or a one-off. It does the full
   bundle-validation and hashing work on every call — see `prepare` — so it is
   the wrong function to call in a loop or per request. The name says so at the
   call site, which is the point: `decide` used to accept a raw bundle and
   silently do this, which made the expensive call the one that looked normal."
  [specification state command]
  (decide (prepare specification) state command))
