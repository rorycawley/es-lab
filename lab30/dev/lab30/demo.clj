(ns lab30.demo
  (:gen-class)
  (:require [lab30.postgres :as postgres]
            [lab30.registry.api :as registry]
            [next.jdbc :as jdbc]))

(def entities
  [{:entity-id #uuid "019c0000-0000-7000-8000-000000000001"
    :registration-number "CH-020.3.000.001-1" :euid "CHEU.000001"
    :name "Straße 1" :legal-form :plc :filing-language :de
    :status :active :registered-on "2020-01-01"}
   {:entity-id #uuid "019c0000-0000-7000-8000-000000000002"
    :registration-number "CH-020.3.000.002-2" :euid "CHEU.000002"
    :name "Société Générale" :legal-form :plc :filing-language :fr
    :status :active :registered-on "2020-02-02"}
   {:entity-id #uuid "019c0000-0000-7000-8000-000000000003"
    :registration-number "CH-020.3.000.003-3" :euid nil
    :name "Vermögensverwaltungsgesellschaft" :legal-form :llc
    :filing-language :de :status :active :registered-on "2020-03-03"}
   {:entity-id #uuid "019c0000-0000-7000-8000-000000000004"
    :registration-number "CH-020.3.000.004-4" :euid nil
    :name "Fratelli D’Angelo" :legal-form :llc :filing-language :it
    :status :active :registered-on "2020-04-04"}
   {:entity-id #uuid "019c0000-0000-7000-8000-000000000005"
    :registration-number "CH-020.3.000.005-5" :euid nil
    :name "Bäckerei Müller und Söhne" :legal-form :llc :filing-language :de
    :status :active :registered-on "2020-05-05"}])

(defn- result-line [result]
  (if-let [found (:found result)]
    (str (name (:rung result)) " -> " (mapv :display-name found))
    (str "no match -> " (:no-matches result))))

(defn -main [& _]
  (postgres/truncate!)
  (let [module (registry/new-module (postgres/datasource))]
    (doseq [entity entities] (registry/register! module entity))
    (println)
    (println "  One filed name; several disposable ways to find it.")
    (println "  --------------------------------------------------")
    (doseq [[language query]
            [[:fr "CH-020.3.000.002-2"]
             [:it "Strasse 1 SA"]
             [:de "Societe Generale"]
             [:fr "Verwaltung"]
             [:it "L'Angelo"]
             [:de "Muler"]]]
      (println (format "  %-4s %-30s %s" (name language) query
                       (result-line (registry/search module
                                                     {:query query
                                                      :ui-language language})))))
    (println)
    (println "  Derived keys for the German compound:")
    (println (jdbc/execute-one!
              (postgres/datasource)
              ["SELECT name, name_ci, name_key, german_parts, name_tsv::text
                  FROM registry.entity
                 WHERE reg_no = 'CH-020.3.000.003-3'"]))
    (shutdown-agents)))
