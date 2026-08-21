(ns lab13.corpus
  "Real events, as they were actually written, in every shape this system has
  ever used.

  This namespace is a test fixture that is also a fitness function. An event
  store's contract is that every schema it has ever written stays readable, and
  the only way to know that still holds is to keep specimens and read them.

  Nothing here may ever be edited. Correcting a shape in the corpus is exactly
  the mistake the corpus exists to catch — the events in production will not
  have been corrected.")

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")

;; ---------------------------------------------------------------------------
;; v1 — the original, from labs 1 through 12. No price: the truck did not
;; record what a cone sold for.
;; ---------------------------------------------------------------------------

(def flavour-sold-v1
  {:event/id       #uuid "018f7a3e-0000-7000-8000-000000000001"
   :event/type     :flavour-sold
   :stream/id      truck-1
   :stream/version 1
   :event/position 1
   :data           {:flavour :vanilla}
   :metadata       {:schema-version 1}})

;; ---------------------------------------------------------------------------
;; v2 — we started recording the price. Ex-VAT, because that is what the till
;; displayed at the time.
;; ---------------------------------------------------------------------------

(def flavour-sold-v2
  {:event/id       #uuid "018f7a3e-0000-7000-8000-000000000002"
   :event/type     :flavour-sold
   :stream/id      truck-1
   :stream/version 2
   :event/position 2
   :data           {:flavour :vanilla :price 2.50M}
   :metadata       {:schema-version 2}})

;; ---------------------------------------------------------------------------
;; v3 — `:price` renamed to `:unit-price`. Still ex-VAT: the number means the
;; same thing, it is just called something else.
;; ---------------------------------------------------------------------------

(def flavour-sold-v3
  {:event/id       #uuid "018f7a3e-0000-7000-8000-000000000003"
   :event/type     :flavour-sold
   :stream/id      truck-1
   :stream/version 3
   :event/position 3
   :data           {:flavour :chocolate :unit-price 2.50M}
   :metadata       {:schema-version 3}})

;; ---------------------------------------------------------------------------
;; The VAT change. NOT a fourth version of :flavour-sold.
;;
;; From here the recorded price includes VAT. Same field name, same type, same
;; range of plausible values — and a different meaning. No upcaster can bridge
;; that, because there is nothing wrong with the old events to fix: they are
;; true statements about a different quantity.
;;
;; So it gets its own type, and the fold handles both.
;; ---------------------------------------------------------------------------

(def flavour-sold-gross
  {:event/id       #uuid "018f7a3e-0000-7000-8000-000000000004"
   :event/type     :flavour-sold-gross
   :stream/id      truck-1
   :stream/version 4
   :event/position 4
   :data           {:flavour :vanilla :unit-price 3.00M}
   :metadata       {:schema-version 1}})

;; ---------------------------------------------------------------------------
;; v4 — `:flavour` written as a string rather than a keyword.
;;
;; Not a domain change at all. The truck sells exactly what it sold before;
;; this is the system correcting a decision it made in lab 1 and cannot go
;; back and unmake, because a keyword is a program symbol and every one of the
;; specimens above had to be translated at each boundary it crossed.
;;
;; Which is the most ordinary kind of schema change there is, and the one the
;; ladder handles best: same fact, different encoding. Note what did *not*
;; happen — the events above still say `:vanilla`, because they do. Editing
;; them is the mistake this namespace exists to catch.
;; ---------------------------------------------------------------------------

(def flavour-sold-v4
  {:event/id       #uuid "018f7a3e-0000-7000-8000-000000000005"
   :event/type     :flavour-sold
   :stream/id      truck-1
   :stream/version 5
   :event/position 5
   :data           {:flavour "vanilla" :unit-price 2.50M}
   :metadata       {:schema-version 4}})

;; And the same correction to the other type, on its own ladder — see
;; `upcast/current-version`, which is a map for exactly this reason.

(def flavour-sold-gross-v2
  {:event/id       #uuid "018f7a3e-0000-7000-8000-000000000006"
   :event/type     :flavour-sold-gross
   :stream/id      truck-1
   :stream/version 6
   :event/position 6
   :data           {:flavour "chocolate" :unit-price 3.00M}
   :metadata       {:schema-version 2}})

(def every-shape
  "One specimen of every shape ever written. The suite folds all of them."
  [flavour-sold-v1
   flavour-sold-v2
   flavour-sold-v3
   flavour-sold-gross
   flavour-sold-v4
   flavour-sold-gross-v2])
