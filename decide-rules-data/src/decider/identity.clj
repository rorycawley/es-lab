(ns decider.identity
  "Semantic identity of a specification: which decision model, which governed
   revision, which exact content.

   This namespace exists to have one definition of that triple. It has no
   dependencies, which is the point -- `decider.dsl` and `decider.core` both
   stamp results with it, and they sit on opposite sides of `decider.schema`,
   so neither can own it without the other reaching across the layering.")

(defn specification-ref
  "Project the identity of `specification` -- see README section 29.

   Every result that leaves `decider.core` carries this under `:spec/ref`,
   whether it is a decision or an invalid-input result, so a reader can always
   tell which specification produced the answer they are holding.

   The matching Malli schema is `decider.schema/SpecificationRef`. The two
   describe the same shape and must change together; `decider.identity-test`
   checks that they still agree."
  [specification]
  {:id      (:spec/id specification)
   :version (:spec/version specification)
   :hash    (:spec/hash specification)})
