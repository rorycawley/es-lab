(ns lab30.normalization-test
  "The Unicode and volatility assumptions from the guide, verified against
  the exact PostgreSQL image the lab runs."
  (:require [clojure.test :refer [deftest is]]
            [lab30.fixture :as fixture]
            [lab30.postgres :as postgres]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(deftest the-database-locale-is-part-of-the-index-contract-test
  (fixture/with-registry
    (fn [_]
      (let [row (jdbc/execute-one!
                 (postgres/admin)
                 ["SELECT datlocprovider::text AS provider, datlocale,
                          current_setting('server_encoding') AS encoding,
                          casefold('Straße') AS folded
                     FROM pg_database WHERE datname = current_database()"]
                 opts)]
        (is (= "UTF8" (:encoding row)))
        (is (= "b" (:provider row)) "builtin locale provider")
        (is (= "PG_UNICODE_FAST" (:datlocale row)))
        (is (= "strasse" (:folded row))
            "the default libc container would have returned straße")))))

(deftest volatility-is-measured-rather-than-copied-from-the-guide-test
  (fixture/with-registry
    (fn [_]
      (let [rows (jdbc/execute!
                  (postgres/admin)
                  ["SELECT n.nspname AS schema, p.proname, p.provolatile::text AS volatility
                       FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                      WHERE p.proname IN ('casefold', 'unaccent', 'f_unaccent')
                      ORDER BY n.nspname, p.proname"]
                  opts)
            volatility (into {} (map (juxt #(str (:schema %) "/" (:proname %))
                                           :volatility)) rows)]
        (is (= "i" (get volatility "pg_catalog/casefold")))
        (is (= "s" (get volatility "public/unaccent"))
            "PostgreSQL 18.4 marks unaccent stable, not immutable")
        (is (= "i" (get volatility "registry/f_unaccent"))
            "the wrapper is our rebuild promise")))))

(deftest search-key-acceptance-corpus-test
  (fixture/with-registry
    (fn [_]
      (doseq [[input expected]
              [["L'Oréal Suisse SA" "loreal suisse sa"]
               ["L’Oréal Suisse SA" "loreal suisse sa"]
               ["Bäckerei Müller & Co." "backerei muller co"]
               ["Straße 1 AG" "strasse 1 ag"]
               ["Società Anonima d'Italia" "societa anonima ditalia"]
               ["ÉTAT" "etat"]
               ["État" "etat"]]]
        (is (= expected
               (:key (jdbc/execute-one!
                      (postgres/datasource)
                      ["SELECT registry.search_key(?) AS key" input]
                      opts)))
            input)))))

(deftest the-driving-edge-makes-java-text-storable-and-nfc-test
  (fixture/with-registry
    (fn [module]
      (let [nfd "Mu\u0308ller\u0000 Holding"
            result (fixture/register! module nfd)
            stored (get-in result [:accepted :name])]
        (is (= "Müller Holding" stored))
        (is (= stored
               (:name (jdbc/execute-one!
                       (postgres/datasource)
                       ["SELECT name FROM registry.entity"]
                       opts))))))))
