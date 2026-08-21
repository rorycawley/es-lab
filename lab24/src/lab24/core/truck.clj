(ns lab24.core.truck
  "The domain: what an Ice Cream truck knows.

  Lab 8's `decide` and `evolve`, with one addition — the truck now knows which
  driver is working it, and refuses a sale to anybody else.

  That addition is the whole of this lab's second authorisation layer, and
  note where it is: **inside the core, in the function that already says no.**
  It needs state, so nothing at the door could have answered it. Nothing about
  a token, a claim, a role or a header reaches this file; the actor arrives as
  a plain value on the command, exactly as a flavour does.")

;; ---------------------------------------------------------------------------
;; evolve : state -> event -> state          (lab 6)
;;
;; The state grew a second key, so stock moved under one of its own. A fold's
;; shape is allowed to change; what may not change is who decides it (lab 13).
;; ---------------------------------------------------------------------------

(def initial-state {:stock {} :driver nil})

(defmulti evolve (fn [_state event] (:event/type event)))

(defmethod evolve :truck-loaded
  [state event]
  (let [{:keys [flavour quantity]} (:data event)]
    (update-in state [:stock flavour] (fnil + 0) quantity)))

(defmethod evolve :flavour-sold
  [state event]
  (update-in state [:stock (get-in event [:data :flavour])] (fnil dec 0)))

(defmethod evolve :driver-assigned
  [state event]
  (assoc state :driver (get-in event [:data :driver-id])))

(defmethod evolve :default
  [state _event]
  state)

(defn replay [events] (reduce evolve initial-state events))

;; ---------------------------------------------------------------------------
;; Two projections of one state, which is ADR-0020's field-level security in
;; its smallest possible form. Same events, same fold, different views — and
;; *which* view a caller gets is decided by the query adapter, not here. The
;; core's job is to make both shapes available and neither one privileged.
;; ---------------------------------------------------------------------------

(defn stock
  "What is on the truck. Not who is driving it."
  [state]
  (:stock state))

(defn operations
  "The same truck, to somebody who rosters it."
  [state]
  {:stock (:stock state) :driver (:driver state)})

;; ---------------------------------------------------------------------------
;; decide : command -> state -> [event]
;; ---------------------------------------------------------------------------

(defn- refuse
  "Say no with a *reason*, not a sentence.

  Lab 23 classified refusals by matching on the exception's message, which
  worked and was fragile — a reworded string silently became a different
  outcome. The reason is data now, and the adapter maps it to a status code
  (`:not-authorised` to 403, `:sold-out` to 422) without the core learning
  that HTTP exists."
  [reason message data]
  (throw (ex-info message (assoc data :reason reason))))

(defn- actor-id [command] (get-in command [:command/actor :id]))

(defmulti decide (fn [command _state] (:command/type command)))

(defmethod decide :assign-driver
  [command _state]
  [{:event/type :driver-assigned :data {:driver-id (get-in command [:data :driver-id])}}])

(defmethod decide :load-truck
  [command _state]
  (let [{:keys [quantity]} (:data command)]
    ;; Loading nothing onto the truck is not a fact. Nothing happened, and
    ;; nothing went wrong either.
    (if (pos? quantity)
      [{:event/type :truck-loaded :data (:data command)}]
      [])))

(defmethod decide :buy-flavour
  [command state]
  (let [flavour   (get-in command [:data :flavour])
        remaining (get-in state [:stock flavour] 0)]

    ;; ── ABAC, and it goes first ──────────────────────────────────────────
    ;;
    ;; Ownership before availability, deliberately. Ask "is there vanilla?"
    ;; before "may you sell?" and the refusals differ by *reason*, which turns
    ;; the endpoint into an inventory oracle for anyone with any valid token.
    ;;
    ;; ADR-0020 calls this attribute-based access control and puts it "inside
    ;; the command handler". Here that is `decide`, and the placement is the
    ;; point: the reactor calls this function too (lab 10), so unlike the RBAC
    ;; gate at the door there is no path that skips it.
    (when-not (= (actor-id command) (:driver state))
      (refuse :not-authorised "Not this truck's driver"
              {:command/type :buy-flavour :actor (actor-id command)}))

    (when-not (pos? remaining)
      (refuse :sold-out "Sold out"
              {:command/type :buy-flavour :flavour flavour :remaining remaining}))

    ;; Selling the last cone is two facts, in the order they became true.
    (if (= 1 remaining)
      [{:event/type :flavour-sold   :data {:flavour flavour}}
       {:event/type :stock-depleted :data {:flavour flavour}}]
      [{:event/type :flavour-sold   :data {:flavour flavour}}])))
