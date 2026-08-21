(ns lab6.evolve
  "Folding a history of events into state, for an Ice Cream truck.

  The state is how many cones of each flavour are left. Nothing stores it:
  it is recomputed from the events every time it is needed.")

;; ---------------------------------------------------------------------------
;; The events. Two the fold cares about, one it doesn't.
;; ---------------------------------------------------------------------------

(defn truck-loaded
  [flavour quantity]
  {:event/id   (random-uuid)
   :event/type :truck-loaded
   :data       {:flavour flavour :quantity quantity}})

(defn flavour-sold
  [flavour]
  {:event/id   (random-uuid)
   :event/type :flavour-sold
   :data       {:flavour flavour}})

(defn stock-depleted
  [flavour]
  {:event/id   (random-uuid)
   :event/type :stock-depleted
   :data       {:flavour flavour}})

;; ---------------------------------------------------------------------------
;; The empty truck. Part of the definition, not an accident of `nil`.
;; ---------------------------------------------------------------------------

(def initial-state
  {:stock     {}
   :last-sold nil})

;; ---------------------------------------------------------------------------
;; evolve : state -> event -> state
;;
;; One event at a time. It applies the event; it never judges it. By the time
;; an event exists the thing already happened, so there is nothing left to
;; refuse — which is why selling a flavour that was never loaded yields a
;; nonsense count rather than an error. Preventing that is `decide`'s job.
;; ---------------------------------------------------------------------------

(defmulti evolve
  (fn [_state event] (:event/type event)))

(defmethod evolve :truck-loaded
  [state event]
  (let [{:keys [flavour quantity]} (:data event)]
    (update-in state [:stock flavour] (fnil + 0) quantity)))

(defmethod evolve :flavour-sold
  [state event]
  (let [flavour (get-in event [:data :flavour])]
    (-> state
        (update-in [:stock flavour] (fnil dec 0))
        (assoc :last-sold flavour))))

;; Every other event type, including ones this namespace has never heard of.
;; A stream is full of facts a given fold has no opinion about; having no
;; opinion has to mean "state unchanged", not "crash".
(defmethod evolve :default
  [state _event]
  state)

(defn replay
  "Rebuild state from a history. This is the whole of event sourcing."
  [events]
  (reduce evolve initial-state events))

;; ---------------------------------------------------------------------------
;; A day's trading.
;; ---------------------------------------------------------------------------

(def morning
  [(truck-loaded "vanilla" 3)
   (truck-loaded "chocolate" 1)])

(def afternoon
  [(flavour-sold "vanilla")
   (flavour-sold "vanilla")
   (flavour-sold "chocolate")
   (stock-depleted "chocolate")])

(def full-day
  (into morning afternoon))
