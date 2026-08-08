(ns platform.runtime.config
  "Loads and validates service configuration at the composition boundary."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def stores #{:memory :sqlite :postgres})

(defn parse-store [value]
  (let [store (keyword value)]
    (if (contains? stores store)
      store
      (throw (ex-info "CART_STORE must be memory, sqlite or postgres"
                      {:value value :allowed stores})))))

(defn public-bind-address? [host]
  (contains? #{"0.0.0.0" "::" "[::]"} host))

(defn validate!
  [{:keys [store http deployment db] :as config}]
  (when-not (contains? stores store)
    (throw (ex-info "Unsupported persistence store"
                    {:store store :allowed stores})))
  (when-not (<= 0 (:port http) 65535)
    (throw (ex-info "PORT must be between 0 and 65535"
                    {:port (:port http)})))
  (when (and (public-bind-address? (:host http))
             (not (:trusted-upstream? deployment)))
    (throw (ex-info
            "A public bind requires TRUSTED_UPSTREAM_ENFORCED=true"
            {:host (:host http)})))
  (when (and (= :postgres store) (str/blank? (:jdbc-url db)))
    (throw (ex-info
            "JDBC_URL or DATABASE_URL is required for PostgreSQL"
            {:store store})))
  config)

(defn read-config
  ([]
   (let [resource (io/resource "config.edn")
         raw       (aero/read-config resource)
         store     (parse-store (:store raw))]
     (read-config store)))
  ([store]
   (let [resource (io/resource "config.edn")
         config   (-> (aero/read-config resource {:profile store})
                      (assoc :store store))]
     (-> (if (nil? (:db config)) (dissoc config :db) config)
         validate!))))
