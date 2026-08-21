(ns lab6.evolve
  "Folding a history of events into state, for an Ice Cream truck.

  The state contains current stock and the last flavour sold. It is derived
  from the event history; a later lab may cache it, but the event history
  remains the source of record.")

;; ---------------------------------------------------------------------------
;; Sample events. Two change this state; two are explicit no-ops.
;; ---------------------------------------------------------------------------

(defn truck-loaded
  [event-id flavour quantity]
  {:event/id   event-id
   :event/type :truck-loaded
   :data       {:flavour flavour :quantity quantity}})

(defn flavour-sold
  [event-id flavour]
  {:event/id   event-id
   :event/type :flavour-sold
   :data       {:flavour flavour}})

(defn stock-depleted
  [event-id flavour]
  {:event/id   event-id
   :event/type :stock-depleted
   :data       {:flavour flavour}})

(defn truck-repainted
  [event-id colour]
  {:event/id   event-id
   :event/type :truck-repainted
   :data       {:colour colour}})

;; ---------------------------------------------------------------------------
;; The empty truck. Part of the definition, not an accident of `nil`.
;; ---------------------------------------------------------------------------

(def initial-state
  {:stock     {}
   :last-sold nil})

;; ---------------------------------------------------------------------------
;; evolve : state -> event -> state
;;
;; One event at a time. It applies each supported event without re-judging the
;; business decision. By the time an event exists the thing already happened,
;; so there is nothing left to refuse — which is why selling a flavour that
;; was never loaded yields a nonsense count rather than an error. Preventing
;; that is `decide`'s job. Unknown semantics are a compatibility error.
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

;; Known facts that this fold deliberately has no opinion about. Naming these
;; no-op handlers is a compatibility decision: it is safe because we know what
;; each fact means and know it cannot affect this decision state.
(defmethod evolve :stock-depleted
  [state _event]
  state)

(defmethod evolve :truck-repainted
  [state _event]
  state)

;; An event type this decision fold does not understand may affect an
;; invariant. Silently ignoring it would let old code decide from incomplete
;; state. Readers for new semantics must be deployed before writers.
(defmethod evolve :default
  [_state event]
  (throw (ex-info "Unknown event type"
                  {:event/type (:event/type event)})))

(defn replay
  "Reconstruct current state from an ordered event history."
  [events]
  (reduce evolve initial-state events))

;; ---------------------------------------------------------------------------
;; A day's trading.
;; ---------------------------------------------------------------------------

(def load-vanilla-id #uuid "018f7a3e-0000-7000-8000-000000000601")
(def load-chocolate-id #uuid "018f7a3e-0000-7000-8000-000000000602")
(def sell-vanilla-1-id #uuid "018f7a3e-0000-7000-8000-000000000603")
(def sell-vanilla-2-id #uuid "018f7a3e-0000-7000-8000-000000000604")
(def sell-chocolate-id #uuid "018f7a3e-0000-7000-8000-000000000605")
(def deplete-chocolate-id #uuid "018f7a3e-0000-7000-8000-000000000606")

(def morning
  [(truck-loaded load-vanilla-id "vanilla" 3)
   (truck-loaded load-chocolate-id "chocolate" 1)])

(def afternoon
  [(flavour-sold sell-vanilla-1-id "vanilla")
   (flavour-sold sell-vanilla-2-id "vanilla")
   (flavour-sold sell-chocolate-id "chocolate")
   (stock-depleted deplete-chocolate-id "chocolate")])

(def full-day
  (into morning afternoon))
