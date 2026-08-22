(ns lab30.registry.register-entity
  "The complete `Register entity` command slice.

  The filed name is the source. NFC is the only canonical rewrite permitted;
  every lookup key is derived and can be rebuilt. Identity is supplied before
  persistence, preserving Lab 4's consistency rule rather than asking a
  sequence in the database to create a half-identified entity."
  (:require [clojure.string :as str]
            [lab30.registry.german :as german]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (java.text Normalizer Normalizer$Form)
           (java.time LocalDate)))

(def Request
  [:map {:closed true}
   [:entity-id :uuid]
   [:registration-number [:string {:min 1 :max 80}]]
   [:euid {:optional true} [:maybe [:string {:min 1 :max 120}]]]
   [:name [:string {:min 1 :max 300}]]
   [:legal-form [:enum :plc :llc :lp :gp :coop :se :branch :other]]
   [:filing-language [:enum :fr :de :it]]
   [:status [:enum :active :removed]]
   [:registered-on [:re #"\d{4}-\d{2}-\d{2}"]]])

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn prepare
  "The driving edge removes U+0000, which Java can hold and Postgres `text`
  cannot, and canonicalises the filed name to NFC."
  [request]
  (let [clean #(some-> % str (str/replace "\u0000" "") str/trim)]
    (-> request
        (update :registration-number clean)
        (update :euid clean)
        (update :name #(some-> % clean
                               (Normalizer/normalize Normalizer$Form/NFC))))))

(defn- public-row [row]
  (when row
    (-> row
        (update :legal-form keyword)
        (update :filing-language keyword)
        (update :status keyword))))

(defn handle!
  [{:keys [datasource]} request]
  (let [{:keys [entity-id registration-number euid name legal-form
                filing-language status registered-on]} request]
    {:accepted
     (public-row
      (jdbc/execute-one!
       datasource
       ["INSERT INTO registry.entity
           (entity_id, reg_no, euid, name, legal_form, filing_lang, status,
            registered_on, removed_at, german_parts, search_version)
         VALUES (?, ?, ?, ?, ?::registry.legal_form, ?::registry.lang, ?, ?,
                 CASE WHEN ? = 'removed' THEN now() END, ?, ?)
         RETURNING entity_id, reg_no AS registration_number, euid, name,
                   legal_form::text AS legal_form,
                   filing_lang::text AS filing_language,
                   status, registered_on"
        entity-id registration-number euid name (clojure.core/name legal-form)
        (clojure.core/name filing-language) (clojure.core/name status)
        (LocalDate/parse registered-on) (clojure.core/name status)
        (german/parts name) german/version]
       opts))}))

(defn rebuild-search!
  "Recompute the one derived key PostgreSQL cannot produce itself.

  Generated columns follow automatically when `german_parts` changes. This is
  intentionally an admin operation over retained source names."
  [{:keys [datasource]}]
  (jdbc/with-transaction [tx datasource]
    (let [rows (jdbc/execute! tx ["SELECT entity_id, name FROM registry.entity"] opts)]
      (doseq [{:keys [entity-id name]} rows]
        (jdbc/execute-one!
         tx
         ["UPDATE registry.entity SET german_parts = ?, search_version = ?
            WHERE entity_id = ?"
          (german/parts name) german/version entity-id]))
      {:rebuilt (count rows) :search-version german/version})))
