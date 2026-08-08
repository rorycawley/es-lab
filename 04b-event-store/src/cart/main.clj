(ns cart.main
  (:require [cart.system :as system]
            [clojure.string :as str]
            [com.stuartsierra.component :as component]))

(defn- env [k]
  (System/getenv k))

(defn- parse-long-env [label value default]
  (if (str/blank? value)
    default
    (try
      (Long/parseLong value)
      (catch NumberFormatException e
        (throw (ex-info (str label " must be an integer") {:value value} e))))))

(defn- parse-double-env [label value default]
  (if (str/blank? value)
    default
    (try
      (Double/parseDouble value)
      (catch NumberFormatException e
        (throw (ex-info (str label " must be a number") {:value value} e))))))

(defn- parse-store [value]
  (case (or value "postgres")
    "postgres" :postgres
    "sqlite"   :sqlite
    "memory"   :memory
    (throw (ex-info "CART_STORE must be postgres, sqlite or memory"
                    {:value value}))))

(defn db-config-from-env []
  (let [db {:jdbc-url  (or (env "JDBC_URL") (env "DATABASE_URL"))
            :username  (env "DB_USERNAME")
            :password  (env "DB_PASSWORD")
            :pool-size (parse-long-env "DB_POOL_SIZE" (env "DB_POOL_SIZE") 10)}]
    (when (str/blank? (:jdbc-url db))
      (throw (ex-info "JDBC_URL is required" {})))
    db))

(defn sqlite-config-from-env []
  {:jdbc-url        (or (env "SQLITE_JDBC_URL")
                        (some->> (env "SQLITE_PATH") (str "jdbc:sqlite:"))
                        "jdbc:sqlite:target/cart-event-store.sqlite3")
   :pool-size       (parse-long-env "SQLITE_POOL_SIZE"
                                    (env "SQLITE_POOL_SIZE")
                                    4)
   :busy-timeout-ms (parse-long-env "SQLITE_BUSY_TIMEOUT_MS"
                                    (env "SQLITE_BUSY_TIMEOUT_MS")
                                    5000)
   :migrate?        true})

(defn config-from-env []
  (let [store (parse-store (env "CART_STORE"))]
    (cond-> {:store store
             :http  {:port (parse-long-env "PORT" (env "PORT") 8080)}
             :retry {:retries     (parse-long-env "RETRY_ATTEMPTS" (env "RETRY_ATTEMPTS") 3)
                     :min-timeout (parse-long-env "RETRY_MIN_TIMEOUT_MS"
                                                  (env "RETRY_MIN_TIMEOUT_MS")
                                                  100)
                     :factor      (parse-double-env "RETRY_FACTOR" (env "RETRY_FACTOR") 1.5)}}
      (= :postgres store) (assoc :db (db-config-from-env))
      (= :sqlite store) (assoc :db (sqlite-config-from-env)))))

(defn -main [& _]
  (let [config (config-from-env)
        system (component/start-system (system/new-system config))]
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. #(component/stop-system system)))
    (println (format "Cart HTTP service listening on http://localhost:%d"
                     (get-in config [:http :port])))
    @(promise)))
