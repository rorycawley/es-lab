(ns cart.config
  "Reads resources/config.edn with aero.

   Config is data in a file, not a pile of getenv calls spread through the
   composition root — resources/config.edn is the only place this service reads
   its environment.

   The store is awkward because it selects which #profile branch of :db
   applies, and aero needs the profile before it reads. So the file is read
   twice: once with no profile, which yields nothing but :store (everything
   under #profile reads as nil), and then again with the validated store as the
   profile. Two reads of a small file at start-up is a cheap price for keeping
   CART_STORE in the config file with everything else."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def stores
  "The stores cart.system knows how to build."
  #{:postgres :sqlite :memory})

(defmethod aero/reader 'sqlite-path
  [_ _ value]
  ;; nil rather than "jdbc:sqlite:" when unset, so an enclosing #or keeps
  ;; falling through rather than settling on a URL with no file in it.
  (when-not (str/blank? value)
    (str "jdbc:sqlite:" value)))

(defn parse-store
  "=> :postgres | :sqlite | :memory

   Validated here rather than by #keyword in the file, because a typo should
   name the legal values instead of producing an unknown keyword that fails
   later inside cart.system."
  [value]
  (if (str/blank? value)
    :postgres
    (let [store (keyword value)]
      (if (contains? stores store)
        store
        (throw (ex-info "CART_STORE must be postgres, sqlite or memory"
                        {:value value :allowed stores}))))))

(defn- validate!
  [{:keys [store db] :as config}]
  (when (and (= :postgres store) (str/blank? (:jdbc-url db)))
    (throw (ex-info "JDBC_URL (or DATABASE_URL) is required when CART_STORE is postgres"
                    {:store store})))
  config)

(defn- read* [profile]
  (aero/read-config (io/resource "config.edn") {:profile profile}))

(defn read-config
  "=> {:store ... :http {...} :retry {...} :db {...}}

   The 1-arity takes an explicit store, which is what tests and the REPL want.
   The 0-arity resolves it from the :store entry in config.edn."
  ([] (read-config (parse-store (:store (read* nil)))))
  ([store]
   (-> (read* store)
       ;; The file's :store is the raw string; the caller's validated keyword
       ;; is what the rest of the system dispatches on.
       (assoc :store store)
       ;; :memory has no database; carrying a nil :db would invite a caller to
       ;; destructure it and get nils rather than a missing key.
       (as-> config (if (nil? (:db config)) (dissoc config :db) config))
       validate!)))
