(ns lab2.command
  "Static examples of a Command: 'buy flavour' request for an Ice Cream truck.")

;; ---------------------------------------------------------------------------
;; The name of an operation, and the data required to perform it.
;; ---------------------------------------------------------------------------

(def buy-flavour-vanilla-command
  {:command/type :buy-flavour
   :data         {:flavour :vanilla}})

(def buy-flavour-chocolate-command
  {:command/type :buy-flavour
   :data         {:flavour :chocolate}})

(def examples
  [buy-flavour-vanilla-command
   buy-flavour-chocolate-command])

;; ---------------------------------------------------------------------------
;; Lab 1's event, repeated here so the pair can be compared side by side.
;; Labs are self-contained; this is the only borrowed value in the namespace.
;;
;; Note what the two maps share and where they differ: same data, same frame,
;; and only the key naming the shape tells you which one you are holding.
;; ---------------------------------------------------------------------------

(def flavour-sold-vanilla-event
  {:event/type :flavour-sold
   :data       {:flavour :vanilla}})

;; ---------------------------------------------------------------------------
;; Addressed. A state-changing command is routed to something, so it has to
;; name what it is routed to. The client picks the truck id, which means it
;; can name the truck before the truck exists.
;; ---------------------------------------------------------------------------

(def buy-flavour-addressed
  {:command/type :buy-flavour
   :data         {:truck-id #uuid "0f1c2b3a-0000-4000-8000-000000000001"
                  :flavour  :vanilla}})

;; ---------------------------------------------------------------------------
;; Carry only what the behaviour needs. Sending the whole truck along means
;; sending a copy of state the handler is about to re-read anyway — state that
;; was already stale when it left, and that the sender has no authority over.
;; ---------------------------------------------------------------------------

(def buy-flavour-carrying-the-whole-truck
  {:command/type :buy-flavour
   :data         {:truck   {:truck-id #uuid "0f1c2b3a-0000-4000-8000-000000000001"
                            :stock    {:vanilla 3 :chocolate 1}
                            :location "Sandymount Strand"
                            :driver   "Aoife"
                            :takings  137.50M}
                  :flavour :vanilla}})
