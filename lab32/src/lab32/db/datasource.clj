(ns lab32.db.datasource
  "Connection pools, one per identity.

  Three logins means three pools, and that is not overhead -- it is the
  boundary. A single pool with a single superuser would make every privilege
  in `001_schemas.sql` decorative, because any component could do anything.

  Gotcha #6 is enforced here rather than hoped for. `FOR UPDATE SKIP LOCKED`
  under REPEATABLE READ or SERIALIZABLE raises serialization failures instead
  of skipping, which turns the dispatcher's claim query from a queue into a
  source of intermittent errors under exactly the concurrency it exists to
  handle. READ COMMITTED is Postgres's default, so this is a statement of
  intent rather than a change -- and `isolation_test.clj` asserts it, because
  a default is something somebody can change in a config file."
  (:require [next.jdbc :as jdbc])
  (:import (com.zaxxer.hikari HikariDataSource)))

(defn pool
  "A HikariCP pool for one login."
  ^HikariDataSource
  [{:keys [jdbc-url user password pool-size]
    :or   {pool-size 10}}]
  (doto (HikariDataSource.)
    (.setJdbcUrl jdbc-url)
    (.setUsername user)
    (.setPassword password)
    (.setMaximumPoolSize pool-size)
    (.setTransactionIsolation "TRANSACTION_READ_COMMITTED")
    (.setPoolName (str "lab32-" user))))

(defn close!
  [^HikariDataSource ds]
  (when ds (.close ds)))

(defn isolation-level
  "What the server thinks this pool's isolation level is.

  Read from a live connection rather than from the Hikari config, because the
  question worth asking is not what we asked for."
  [datasource]
  (:transaction_isolation
   (jdbc/execute-one! datasource ["SHOW transaction_isolation"])))
