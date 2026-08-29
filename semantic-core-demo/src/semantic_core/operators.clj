(ns semantic-core.operators)

(def operators
  {:core/= =
   :core/not= not=
   :core/and (fn [& xs] (every? true? xs))
   :core/or (fn [& xs] (boolean (some true? xs)))
   :core/not not
   :core/< <
   :core/<= <=
   :core/> >
   :core/>= >=
   :core/contains? contains?})
