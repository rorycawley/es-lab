(ns lab32.postgres
  "One real Postgres for the whole JVM, migrated once.

  Labs 19 through 30 all do this and for the same reason: starting a container
  per test turns a three-second suite into a four-minute one, and the thing
  being tested is never container startup. Isolation comes from truncating
  between scenarios instead.

  This lab has one wrinkle those did not. Migration 001 creates *roles*, which
  are cluster-level rather than database-level objects, so re-running it
  against the same container fails. The migration ledger already prevents that,
  which is why `migrate!` is called here once and the `:migrator` component
  then finds nothing to do."
  (:require [lab32.config :as config]
            [lab32.db.migrate :as migrate]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (org.testcontainers.containers PostgreSQLContainer)
           (org.testcontainers.utility DockerImageName)))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(def image
  (-> (DockerImageName/parse "postgres:18.4-alpine")
      (.asCompatibleSubstituteFor "postgres")))

(defonce ^:private environment
  (delay
    (let [container (doto (PostgreSQLContainer. image)
                      (.withDatabaseName "eslab")
                      (.start))
          admin     (jdbc/get-datasource
                     {:jdbcUrl  (.getJdbcUrl container)
                      :user     (.getUsername container)
                      :password (.getPassword container)})]
      (migrate/migrate! admin)
      {:container container :admin admin})))

(defn admin
  "A datasource with rights over everything. For fixtures and assertions only:
  no production code path connects as this."
  []
  (:admin @environment))

(defn overrides
  "Config overlay pointing at the container, with both schedulers off.

  Turning the reconciler and the inbox workers off is not avoiding the real
  code path -- the tests call the very same `drain!` those schedulers call. It
  removes a race between the assertion and a background thread, so that a test
  which fails means something went wrong rather than something went slowly."
  []
  (let [{:keys [container]} @environment]
    {:database   {:jdbc-url (.getJdbcUrl container)
                  :admin    {:user     (.getUsername container)
                             :password (.getPassword container)}}
     :reconciler {:interval-ms nil}
     :inbox      {:interval-ms nil
                  ;; Acceptance test 6 exhausts a message's attempts. With the
                  ;; production one-second base that would be seven seconds of
                  ;; sleeping to observe something that has nothing to do with
                  ;; time.
                  :backoff-seconds 0}
     :dispatcher {:backoff-seconds 0}
     ;; The retention sweep is driven by hand in `retention_test.clj`, for the
     ;; same reason the other two schedulers are off: a background thread
     ;; deleting rows while a test counts them is not a test.
     :retention  {:interval-ms nil :hours 24}
     :http       {:enabled? false}}))

(defn config
  ([] (config {}))
  ([extra] (config/configure (merge-with merge (overrides) extra))))

(defn truncate!
  "Empty every table the modules own, keeping the migration ledger.

  `RESTART IDENTITY` matters more here than in earlier labs: several tests
  assert on `seq` ordering, and a sequence carried over from the previous test
  would make those assertions depend on what ran before them."
  []
  (jdbc/execute!
   (admin)
   ["TRUNCATE accounts.event_stream,
              messaging.outbox,
              compliance.inbox,
              compliance.flagged_transactions
      RESTART IDENTITY"]))

;; ---------------------------------------------------------------------------
;; Looking at the tables directly.
;;
;; These connect as the admin role, deliberately. A test that asserts "the
;; outbox row is still PENDING" has to see something no module is allowed to
;; see -- that is the whole point of the privilege split in migration 001 --
;; so the assertions use an identity the application never has.
;;
;; They live here rather than in the test fixture because the demo needs them
;; too, and `dev/` is on both aliases' paths while `test/` is not.
;; ---------------------------------------------------------------------------

(defn query
  ([sql] (query sql []))
  ([sql params] (jdbc/execute! (admin) (into [sql] params) opts)))

(defn query-one
  ([sql] (query-one sql []))
  ([sql params] (jdbc/execute-one! (admin) (into [sql] params) opts)))

(defn outbox-rows []
  (query "SELECT * FROM messaging.outbox ORDER BY seq"))

(defn inbox-rows
  ([] (inbox-rows "compliance"))
  ([schema] (query (str "SELECT * FROM " schema ".inbox ORDER BY seq"))))

(defn dead-letter-rows
  "Inbox messages that exhausted their attempts. Still in the same table --
  a dead letter here is a status, not another queue."
  ([] (dead-letter-rows "compliance"))
  ([schema] (query (str "SELECT * FROM " schema ".inbox WHERE status = 'FAILED' ORDER BY seq"))))

(defn event-rows []
  (query "SELECT * FROM accounts.event_stream ORDER BY seq"))

(defn flagged-rows []
  (query "SELECT * FROM compliance.flagged_transactions ORDER BY flagged_at, event_id"))

(defn set-notify-trigger!
  "Turn Phase 2's doorbell on or off.

  Acceptance test 9 asks for the trigger to be disabled entirely and the whole
  Phase 1 suite re-run. `ALTER TABLE ... DISABLE TRIGGER` is that, and it is
  better than migrating a second database without migration 006: the trigger
  is provably absent from the running system rather than absent from a
  different one."
  [on?]
  (query (str "ALTER TABLE messaging.outbox "
              (if on? "ENABLE" "DISABLE")
              " TRIGGER trg_outbox_notify")))

(defn notify-trigger-enabled?
  []
  (= "O" (:tgenabled (query-one "SELECT tgenabled FROM pg_trigger
                                  WHERE tgname = 'trg_outbox_notify'"))))
