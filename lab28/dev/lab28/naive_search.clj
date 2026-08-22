(ns lab28.naive-search
  "The implementation everybody writes first, kept so it can be measured.

  It lives in `dev/` rather than `src/` because it is a comparison harness and
  not a use case -- but it is real code running against the real table, in the
  spirit of lab 0's persistence-shaped model and lab 16's three designs. The
  bill arrives in `search_plan_test.clj`."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(def like-sql
  "SELECT product_id, product_name
     FROM catalog.product
    WHERE description LIKE ?
    LIMIT ?")

(defn like-search
  "Substring matching. No stemming, no ranking, no index that can help it."
  [datasource term limit]
  (jdbc/execute! datasource [like-sql (str "%" term "%") limit] opts))

(defn explain
  "The plan Postgres chose for `sql`, as one string.

  `EXPLAIN` without ANALYZE, so nothing here depends on how fast the machine
  running it happens to be, and without costs, so nothing depends on how the
  planner happened to price it."
  [datasource sql params]
  (->> (jdbc/execute! datasource
                      (into [(str "EXPLAIN (COSTS OFF) " sql)] params)
                      {:builder-fn rs/as-unqualified-lower-maps})
       (map (comp str first vals))
       (str/join "\n")))

(defn explain-without-seqscan
  "The plan Postgres would choose if it could not scan the table.

  This is not a way to make a slow query look fast. It answers a different and
  more honest question than the default plan does -- *is there an index here
  at all?* -- and it answers it without depending on table statistics, which
  drift. A query that still scans with `enable_seqscan` off has no index it
  could ever use."
  [datasource sql params]
  (jdbc/with-transaction [tx datasource]
    (jdbc/execute! tx ["SET LOCAL enable_seqscan = off"])
    (explain tx sql params)))
