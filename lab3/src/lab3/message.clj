(ns lab3.message
  "Static examples of an Integration Message: telling another module/system
  that 'flavour sold' happened, for an Ice Cream truck.")

(def flavour-sold-vanilla-integration-message
  {:message/type :flavour-sold
   :payload      {:flavour :vanilla}})

(def flavour-sold-chocolate-integration-message
  {:message/type :flavour-sold
   :payload      {:flavour :chocolate}})

(def examples
  [flavour-sold-vanilla-integration-message
   flavour-sold-chocolate-integration-message])
