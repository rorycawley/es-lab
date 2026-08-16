(ns lab2.command
  "Static examples of a Command: 'buy flavour' request for an Ice Cream truck.")

(def buy-flavour-vanilla-command
  {:command/type :buy-flavour
   :data         {:flavour :vanilla}})

(def buy-flavour-chocolate-command
  {:command/type :buy-flavour
   :data         {:flavour :chocolate}})

(def examples
  [buy-flavour-vanilla-command
   buy-flavour-chocolate-command])
