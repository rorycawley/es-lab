(ns semantic-core.engine)

(defn value-at [m path]
  (get-in m path))

(declare evaluate)

(defn evaluate
  "Evaluate the deliberately small semantic expression language."
  [operators env expr]
  (if-not (vector? expr)
    expr
    (let [[tag & xs] expr]
      (case tag
        :value (first xs)
        :state (value-at (:state env) (first xs))
        :input (value-at (:input env) (first xs))
        :op (let [[op-id & args] xs
                  f (get operators op-id)]
              (when-not f
                (throw (ex-info "Unknown operator" {:operator op-id})))
              (apply f (map #(evaluate operators env %) args)))
        (mapv #(evaluate operators env %) expr)))))

(defn render
  "Render a data template by evaluating embedded expressions."
  [operators env x]
  (cond
    (and (vector? x) (contains? #{:value :state :input :op} (first x)))
    (evaluate operators env x)

    (map? x)
    (into {} (map (fn [[k v]] [k (render operators env v)]) x))

    (vector? x)
    (mapv #(render operators env %) x)

    (set? x)
    (set (map #(render operators env %) x))

    :else x))

(defn rule-result [operators env rule]
  (let [passed? (boolean (evaluate operators env (:assert rule)))]
    {:rule/id (:rule/id rule)
     :result (if passed? :pass :fail)
     :reject (:reject rule)}))

(defn matching-decision [bundle command]
  (some #(when (= (:on %) (:command/type command)) %) (:decisions bundle)))

(defn decide
  "Bundle × Command × AggregateState -> DecisionResult"
  [operators bundle command state]
  (let [definition (matching-decision bundle command)]
    (when-not definition
      (throw (ex-info "No decision definition" {:command/type (:command/type command)})))
    (let [rules-by-id (into {} (map (juxt :rule/id identity) (:rules bundle)))
          env {:state state :input command}
          results (mapv #(rule-result operators env (get rules-by-id %)) (:rules definition))
          failed (some #(when (= :fail (:result %)) %) results)]
      (if failed
        {:status :rejected
         :events []
         :reason (:reject failed)
         :evidence {:decision/id (:decision/id definition)
                    :rules results}}
        {:status :accepted
         :events (mapv #(render operators env %) (:emit definition))
         :evidence {:decision/id (:decision/id definition)
                    :rules results}}))))

(defn apply-update [operators input state [op path expr]]
  (let [env {:state state :input input}
        value (evaluate operators env expr)]
    (case op
      :set (assoc-in state path value)
      :conj (update-in state path (fnil conj []) value)
      :disj (update-in state path (fnil disj #{}) value)
      (throw (ex-info "Unknown state update" {:update/op op})))))

(defn transition
  "FSM × FSMState × Event -> FSMState"
  [fsm current event]
  (or (some (fn [{:keys [from on to]}]
              (when (and (= from current) (= on (:event/type event))) to))
            (:transitions fsm))
      current))

(defn evolve
  "Bundle × State × Event -> State"
  [operators bundle state event]
  (let [state-model (:state-model bundle)
        event-def (some #(when (= (:on %) (:event/type event)) %) (:on-events state-model))
        after-updates (reduce #(apply-update operators event %1 %2)
                              state
                              (:updates event-def))
        fsms-by-id (into {} (map (juxt :fsm/id identity) (:fsms bundle)))]
    (reduce
     (fn [s [path fsm-id]]
       (update-in s path #(transition (get fsms-by-id fsm-id) % event)))
     after-updates
     (:fsm-paths state-model))))

(defn hydrate [operators bundle events]
  (reduce #(evolve operators bundle %1 %2)
          (get-in bundle [:state-model :initial])
          events))

(defn matching-reaction [operators workflow message state]
  (let [env {:state state :input message}]
    (some
     (fn [reaction]
       (when (and (= (:on reaction) (:event/type message))
                  (or (nil? (:when reaction))
                      (evaluate operators env (:when reaction))))
         reaction))
     (:reactions workflow))))

(defn react
  "Bundle × Message × ProcessState -> ReactionResult"
  [operators bundle message process-state]
  (let [workflow (:workflow bundle)
        state (or process-state (:initial workflow))
        reaction (matching-reaction operators workflow message state)]
    (if-not reaction
      {:events [] :commands [] :evidence nil}
      (let [env {:state state :input message}]
        {:events (mapv #(render operators env %) (:emit-events reaction))
         :commands (mapv #(render operators env %) (:emit-commands reaction))
         :evidence {:workflow/id (:workflow/id workflow)
                    :reaction/id (:reaction/id reaction)}}))))

(defn evolve-process
  "Apply workflow-local process events using the same state update algebra."
  [operators bundle state event]
  (let [workflow (:workflow bundle)
        event-def (some #(when (= (:on %) (:event/type event)) %) (:on-events workflow))]
    (reduce #(apply-update operators event %1 %2)
            state
            (:updates event-def))))
