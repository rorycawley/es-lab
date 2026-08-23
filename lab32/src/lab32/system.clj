(ns lab32.system
  "One process, two modules, three database identities, one transport.

  Component returns here after five labs without it, and the reason is
  narrow: this lab is the first since lab 24 to contain things that are
  *running* rather than merely callable. A connection pool, a scheduled
  executor and (from Phase 2) a thread blocked on a socket all have to be
  started in an order and stopped in the reverse one. Labs 25 to 29 had no such
  things -- a module there was a map of closures, and stopping one meant
  nothing -- so a plain composition function was the honest choice. It would
  not be here: a suite that leaks a reconciler thread into the next test fails
  in a way that looks like a messaging bug.

  This is also the only namespace that may name both modules' public APIs.
  Neither module may name the other at all, and `architecture_test.clj`
  enforces it by scanning `ns` forms."
  (:require [com.stuartsierra.component :as component]
            [lab32.accounts.api :as accounts]
            [lab32.accounts.contract :as accounts-contract]
            [lab32.compliance.api :as compliance]
            [lab32.compliance.contract :as compliance-contract]
            [lab32.config :as config]
            [lab32.db.datasource :as datasource]
            [lab32.db.migrate :as migrate]
            [lab32.http.server :as server]
            [lab32.messaging.dispatcher :as dispatcher]
            [lab32.messaging.inbox :as inbox]
            [lab32.messaging.listener :as listener]
            [lab32.messaging.outbox :as outbox]
            [lab32.messaging.reconciler :as reconciler]
            [lab32.messaging.router :as router]
            [lab32.messaging.worker :as worker]))

(def contracts
  "Every module's declared contract. The routing table is folded out of these,
  so adding a module here is the whole of wiring it up."
  [accounts-contract/contract
   compliance-contract/contract])

;; ---------------------------------------------------------------------------
;; Datasources
;; ---------------------------------------------------------------------------

(defrecord Datasources [config pools]
  component/Lifecycle
  (start [this]
    (if pools
      this
      (assoc this :pools
             (into {} (for [identity [:admin :accounts :compliance :messaging]]
                        [identity (datasource/pool (config/connection config identity))])))))
  (stop [this]
    (run! datasource/close! (vals pools))
    (assoc this :pools nil)))

(defn pool-for [datasources identity]
  (or (get-in datasources [:pools identity])
      (throw (ex-info "No pool for this identity" {:reason :unknown-identity
                                                   :identity identity}))))

;; ---------------------------------------------------------------------------
;; Migrator
;; ---------------------------------------------------------------------------

(defrecord Migrator [datasources applied]
  component/Lifecycle
  (start [this]
    (assoc this :applied (migrate/migrate! (pool-for datasources :admin))))
  (stop [this] (assoc this :applied nil)))

;; ---------------------------------------------------------------------------
;; Modules
;; ---------------------------------------------------------------------------

(defrecord AccountsModule [datasources migrator new-id module]
  component/Lifecycle
  (start [this]
    (assoc this :module (accounts/new-module (pool-for datasources :accounts)
                                             {:new-id (or new-id random-uuid)})))
  (stop [this] (assoc this :module nil)))

(defrecord ComplianceModule [datasources migrator module]
  component/Lifecycle
  (start [this]
    (assoc this :module (compliance/new-module (pool-for datasources :compliance))))
  (stop [this] (assoc this :module nil)))

;; ---------------------------------------------------------------------------
;; Transport
;; ---------------------------------------------------------------------------

(defrecord Dispatcher [config datasources event-router instance]
  component/Lifecycle
  (start [this]
    (assoc this :instance (dispatcher/dispatcher (pool-for datasources :messaging)
                                                 event-router
                                                 (:dispatcher config))))
  (stop [this] (assoc this :instance nil)))

(defrecord Reconciler [config dispatcher executor]
  component/Lifecycle
  (start [this]
    ;; A nil interval means "do not schedule". The suite starts the system that
    ;; way and drives `drain!` by hand, because a test that asserts an event
    ;; arrived should not also be racing a background thread that might have
    ;; delivered it already -- or not yet.
    (if-let [interval (get-in config [:reconciler :interval-ms])]
      (assoc this :executor
             (reconciler/every! "reconciler" interval
                                #(dispatcher/drain! (:instance dispatcher))))
      this))
  (stop [this]
    (reconciler/stop! executor)
    (assoc this :executor nil)))

(defrecord NotifyListener [config dispatcher handle]
  component/Lifecycle
  (start [this]
    ;; Off unless asked for, and the suite leaves it off for most of its run.
    ;; That is not avoiding the feature -- it is the claim: the Phase 1 tests
    ;; all pass with no fast path at all, and `fast_path_test.clj` runs the
    ;; same scenarios with it on and gets the same answers.
    (if (get-in config [:listener :enabled?])
      (assoc this :handle (listener/start! (config/connection config :messaging)
                                           (:listener config)
                                           #(dispatcher/drain! (:instance dispatcher))))
      this))
  (stop [this]
    (listener/stop! handle)
    (assoc this :handle nil)))

(defrecord InboxWorkers [config datasources event-router compliance overrides
                         workers executors]
  component/Lifecycle
  (start [this]
    ;; `overrides` substitutes the handler for one module. That is not a test
    ;; hook bolted on: which function consumes a module's messages is a
    ;; composition-root decision exactly like which payment provider a system
    ;; uses, and lab 29 made the same argument for the same reason. Acceptance
    ;; test 6 uses it to wire a handler that always throws.
    (let [handlers (merge {:compliance (compliance/handler (:module compliance))}
                          overrides)
          built    (into {}
                         (for [module (router/consuming-modules event-router)]
                           [module
                            (worker/worker
                             (pool-for datasources module)
                             (merge (:inbox config)
                                    {:module  module
                                     :schema  (router/schema-of event-router module)
                                     :handler (or (get handlers module)
                                                  (throw (ex-info
                                                          "A module consumes events but no handler is wired"
                                                          {:reason :unwired-consumer
                                                           :module module})))}))]))]
      (assoc this
             :workers built
             :executors (when-let [interval (get-in config [:inbox :interval-ms])]
                          (mapv (fn [[module w]]
                                  (reconciler/every! (str "inbox-" (name module))
                                                     interval
                                                     #(worker/drain! w)))
                                built)))))
  (stop [this]
    (run! reconciler/stop! executors)
    (assoc this :workers nil :executors nil)))

(defrecord Retention [config datasources event-router executor]
  component/Lifecycle
  (start [this]
    ;; §7. The queues are pruned and `accounts.event_stream` never is, and that
    ;; asymmetry is the lab's argument in one component. Revolut keep 24 hours
    ;; of log for the same reason: what the outbox held was a work item, and
    ;; what the stream holds is the history the work was about.
    (if-let [interval (get-in config [:retention :interval-ms])]
      (let [hours (get-in config [:retention :hours])]
        (assoc this :executor
               (reconciler/every!
                "retention" interval
                (fn []
                  (outbox/prune! (pool-for datasources :messaging) hours)
                  (doseq [module (router/consuming-modules event-router)]
                    (inbox/prune! (pool-for datasources module)
                                  (router/schema-of event-router module)
                                  hours))))))
      this))
  (stop [this]
    (reconciler/stop! executor)
    (assoc this :executor nil)))

;; ---------------------------------------------------------------------------
;; HTTP
;; ---------------------------------------------------------------------------

(defrecord Http [config accounts compliance server]
  component/Lifecycle
  (start [this]
    (if (get-in config [:http :enabled?])
      (assoc this :server (server/start! {:accounts   (:module accounts)
                                          :compliance (:module compliance)
                                          :replay     (:replay this)}
                                         (:http config)))
      this))
  (stop [this]
    (server/stop! server)
    (assoc this :server nil)))

;; ---------------------------------------------------------------------------
;; The map
;; ---------------------------------------------------------------------------

(defn system
  "The component map. `options` carries test seams -- `:new-id` for
  deterministic identities, and nothing else so far."
  ([config] (system config {}))
  ([config {:keys [new-id handlers]}]
   (component/system-map
    :config       config
    ;; Built here rather than in a component, so that a set of contracts that
    ;; does not add up fails while the map is being constructed -- before
    ;; anything has opened a socket to be left dangling.
    :event-router (router/router contracts)
    :datasources  (component/using (map->Datasources {}) [:config])
    :migrator     (component/using (map->Migrator {}) [:datasources])
    :accounts     (component/using (map->AccountsModule {:new-id new-id})
                                   [:datasources :migrator])
    :compliance   (component/using (map->ComplianceModule {}) [:datasources :migrator])
    :dispatcher   (component/using (map->Dispatcher {})
                                   [:config :datasources :event-router])
    :reconciler   (component/using (map->Reconciler {}) [:config :dispatcher])
    ;; The fast path. Same dependencies as the reconciler, because it does the
    ;; same job by a different route -- which is the point (§5).
    :notify-listener (component/using (map->NotifyListener {}) [:config :dispatcher])
    :inbox-workers (component/using (map->InboxWorkers {:overrides handlers})
                                    [:config :datasources :event-router :compliance])
    :retention    (component/using (map->Retention {})
                                   [:config :datasources :event-router])
    :http         (component/using (map->Http {}) [:config :accounts :compliance]))))

(defn start
  ([] (start (config/configure)))
  ([config] (start config {}))
  ([config options] (component/start (system config options))))

(defn stop [system] (component/stop system))

;; ---------------------------------------------------------------------------
;; Driving it by hand
;;
;; The suite runs with both schedulers off and calls these. That is not a
;; testing shortcut around the real path -- it is the same `drain!` the
;; reconciler calls, invoked by a test instead of by a clock.
;; ---------------------------------------------------------------------------

(defn accounts-module   [system] (get-in system [:accounts :module]))
(defn compliance-module [system] (get-in system [:compliance :module]))

(defn dispatch!
  "One dispatcher pass: outbox -> inboxes."
  [system]
  (dispatcher/drain! (get-in system [:dispatcher :instance])))

(defn work-inboxes!
  "One pass of every inbox worker: inbox -> projections."
  [system]
  (into {} (for [[module w] (get-in system [:inbox-workers :workers])]
             [module (worker/drain! w)])))

(defn settle!
  "Drain everything until nothing moves.

  Each pass can create work for the next -- a dispatched message becomes an
  inbox message, and a module that published in response would create another
  outbox row -- so this loops rather than draining once. `guard` stops a bug
  from turning into a hang."
  ([system] (settle! system 20))
  ([system guard]
   (loop [remaining guard passes 0]
     (let [dispatched (dispatch! system)
           worked     (work-inboxes! system)
           moved      (+ (if (number? dispatched) dispatched 0)
                         (reduce + (map (comp :handled val) worked)))]
       (if (and (pos? moved) (pos? remaining))
         (recur (dec remaining) (inc passes))
         {:passes (inc passes)})))))

(defn replay!
  "Rebuild a module's read model from the event stream. Phase 4.

  Three roles, three steps, and the split is not ceremony -- it falls out of
  the privileges each identity holds:

    compliance  clears its own projection *and its inbox*, because the unique
                constraint would otherwise discard the replay as a duplicate
    messaging   resurrects the outbox rows it still holds; `event_id` is
                UNIQUE, so a fact still sitting there cannot simply be
                re-inserted, and only the transport may update it
    accounts    re-derives the messages from the stream and enqueues any the
                retention sweep has already removed

  Nobody could do the other two steps, which is the boundary working. The
  composition root is the only thing that knows all three exist, which is
  exactly its job."
  [system module]
  (when-not (= :compliance module)
    (throw (ex-info "No such module" {:reason :unknown-module :module module})))
  (compliance/clear! (compliance-module system))
  (let [consumed (:consumes-events compliance-contract/contract)]
    (outbox/requeue! (pool-for (:datasources system) :messaging) consumed))
  (accounts/republish! (accounts-module system)))

(defn handler
  "The HTTP handler, without a server. Lab 23's arrangement."
  [system]
  (server/handler {:accounts   (accounts-module system)
                   :compliance (compliance-module system)
                   :replay     #(replay! system %)}))
