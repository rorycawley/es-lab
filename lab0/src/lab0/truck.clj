(ns lab0.truck
  "An Ice Cream truck, as the people who run one would describe it.

  This is the whole model. Read it looking for a database, an HTTP request, a
  message broker or a framework — there is none, and that absence is the
  point rather than a stage the code has not reached yet.

  What is here instead: the handful of things that have to be true about a
  truck for anyone to answer a question about it. Two invariants, four
  functions, and a `require` list with `clojure.*` and nothing else in it.

  ## A model is a reduction, on purpose

  A model train is not a small real train. It is a deliberate reduction:
  somebody decided which resemblances mattered and threw the rest away, and
  the throwing away is what makes it useful for the thing it is for.

  A truck the business runs has a registration, a paint colour, a chime tune,
  a tyre pressure, an insurance renewal date and a driver with opinions about
  radio. Every one of those is true and none of them is here — not because
  they are unimportant, but because none of them can change the answer to any
  question this model exists to answer.

      Essentially all models are wrong, but some are useful.
        — George E. P. Box

  `model_test.clj` turns that from a principle into a criterion you can run.")

;; ---------------------------------------------------------------------------
;; State
;;
;; A map. Not a class, not a row, not an entity with an identity column — a
;; value, of the kind the language already gives you. `{:stock {\"vanilla\" 3}}`
;; is a complete truck for every purpose below.
;; ---------------------------------------------------------------------------

(def empty-truck {:stock {}})

(defn stock-of
  [truck flavour]
  (get (:stock truck) flavour 0))

(defn total-stock
  [truck]
  (reduce + 0 (vals (:stock truck))))

;; ---------------------------------------------------------------------------
;; The invariants
;;
;; Each is a named predicate, which is the part worth noticing. A rule with a
;; name can be pointed at, tested on its own, and read aloud to the person who
;; asked for it. A rule that only exists as an `if` in the middle of a method
;; that also writes to a database can be none of those things — see
;; `models/truck.clj`, where exactly that has happened.
;; ---------------------------------------------------------------------------

(def capacity
  "The truck holds forty cones. The business said so."
  40)

(defn sellable?
  "You cannot sell what you have not got."
  [truck flavour]
  (pos? (stock-of truck flavour)))

(defn room-for?
  "You cannot load more than the truck holds."
  [truck quantity]
  (<= (+ (total-stock truck) quantity) capacity))

;; ---------------------------------------------------------------------------
;; The operations
;;
;; Values in, a value out. Nothing is saved, because saving is not a domain
;; concept — no ice cream seller has ever said "and then I persist the truck".
;; ---------------------------------------------------------------------------

(defn sell
  [truck flavour]
  (when-not (sellable? truck flavour)
    (throw (ex-info "Sold out" {:flavour flavour})))
  (update-in truck [:stock flavour] dec))

(defn load-cones
  [truck flavour quantity]
  (when-not (room-for? truck quantity)
    (throw (ex-info "No room on the truck"
                    {:flavour flavour :quantity quantity
                     :held (total-stock truck) :capacity capacity})))
  (update-in truck [:stock flavour] (fnil + 0) quantity))
