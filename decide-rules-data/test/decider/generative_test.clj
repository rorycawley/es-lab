(ns decider.generative-test
  "Property-based coverage of the generic interpreter — README section 51.

   Written as a `defspec` rather than a loop over `gen/generate`, so that a
   failure prints the seed and the shrunk input. A generated state can be forty
   keys deep; without shrinking and a seed, a CI failure here is a screenshot
   of a problem rather than a reproduction of one."
  (:require
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [decider.bundle :as bundle]
   [decider.core :as decider]
   [decider.fixtures :as fixtures]
   [decider.schema :as schema]
   [malli.core :as m]
   [malli.generator :as mg]))

(def prepared-by-id
  "Every bundle, prepared once. Preparing inside the property would re-hash and
   re-validate the bundle on every trial."
  (into {}
        (map (fn [path]
               (let [prepared (bundle/load-prepared path)]
                 [(:spec/id (decider/specification prepared)) prepared])))
        fixtures/resource-paths))

(def generators-by-id
  "State and command generators, built once per bundle. `mg/generator` compiles
   a schema, so building these per trial would dominate the run."
  (into {}
        (map (fn [specification]
               [(:spec/id specification)
                {:state   (mg/generator (:state/schema specification))
                 :command (mg/generator (:command/schema specification))}]))
        (fixtures/load-all)))

(def decision-input
  "A `[spec-id state command]` triple.

   The bundle is identified rather than embedded so that a failing case prints
   as something readable and re-runnable, instead of a prepared specification
   full of compiled functions."
  (gen/bind
   (gen/elements (vec (keys generators-by-id)))
   (fn [spec-id]
     (let [{:keys [state command]} (generators-by-id spec-id)]
       (gen/tuple (gen/return spec-id) state command)))))

(def valid-decision?
  "Compiled once. `m/validate` compiles the schema it is handed, so calling it
   inside a property recompiles `Decision` on every trial — the same mistake
   `decider.core/prepare` exists to stop making."
  (m/validator schema/Decision))

(def valid-result?
  (m/validator schema/Result))

(def trials
  "700 cases, none larger than size 40.

   The size cap is what makes this run in seconds rather than a minute. Left
   uncapped, `defspec` grows generated values towards size 200, and a
   `[:map-of :string [:map ...]]` at that size is a state with hundreds of
   entries. The rules read a handful of paths, so the extra bulk buys no
   coverage and costs about 25× the runtime."
  {:num-tests 700 :max-size 40})

(defspec generated-valid-inputs-produce-valid-decisions trials
  (prop/for-all [[spec-id state command] decision-input]
    (let [result (decider/decide (prepared-by-id spec-id) state command)]
      (and (= :decision (:result/type result))
           (valid-decision? (:decision result))
           (valid-result? result)))))

(defspec structurally-valid-input-never-throws trials
  ;; The interpreter is untyped (README section 23), so a bundle can ask for
  ;; something that fails at runtime. This asserts the shipped bundles do not:
  ;; whatever the schemas admit, the rules can evaluate.
  (prop/for-all [[spec-id state command] decision-input]
    (some? (decider/decide (prepared-by-id spec-id) state command))))
