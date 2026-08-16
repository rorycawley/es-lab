(ns lab1.event
  "Static examples of a Domain Event: 'flavour sold' from an Ice Cream truck.")

(def flavour-sold-vanilla
  {:event/type :flavour-sold
   :flavour    :vanilla})

(def flavour-sold-chocolate
  {:event/type :flavour-sold
   :flavour    :chocolate})

(def flavour-sold-strawberry
  {:event/type :flavour-sold
   :flavour    :strawberry})

(def examples
  [flavour-sold-vanilla
   flavour-sold-chocolate
   flavour-sold-strawberry])