(ns registry.registration.adapters.postgres-register-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jsonista.core :as json]
            [next.jdbc    :as jdbc]
            [next.jdbc.date-time]
            [registry.db.migrations                              :as migrations]
            [registry.registration.adapters.postgres-register    :as sut]
            [registry.registration.ports.register-port           :as register-port])
  (:import [java.time Instant]
           [org.postgresql.util PGobject]
           [org.testcontainers.containers PostgreSQLContainer]))

(def ^:dynamic *ds* nil)

(defn with-postgres [f]
  (let [container (PostgreSQLContainer. "postgres:18.4-alpine")]
    (.start container)
    (try
      (let [ds (jdbc/get-datasource {:jdbcUrl  (.getJdbcUrl container)
                                     :user     (.getUsername container)
                                     :password (.getPassword container)})]
        (migrations/migrate! ds)
        (binding [*ds* ds]
          (f)))
      (finally
        (.stop container)))))

(defn truncate [f]
  (jdbc/execute! *ds* ["TRUNCATE TABLE registration_events, register_projection"])
  (f))

(use-fixtures :once with-postgres)
(use-fixtures :each truncate)

(defn- ->pgobject [value]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-value-as-string value))))

(defn- insert-company! [ds & {:keys [registration-number company-name]
                              :or   {registration-number "REG-001"
                                     company-name        "Acme Ltd"}}]
  (let [occurred-at (Instant/now)
        event-data  {"event/type" "company-registered"
                     "company/name" company-name
                     "registration/number" registration-number
                     "registered-office-address" {"address-line-1" "1 Main St"
                                                  "city" "Dublin"
                                                  "country" "IE"}
                     "occurred-at" (str occurred-at)}]
    (jdbc/execute-one! ds
                       ["INSERT INTO registration_events
                           (id, aggregate_id, sequence_number, event_type,
                            event_data, occurred_at, causation_id, correlation_id)
                         VALUES (?, ?, 1, 'company-registered', ?, ?, ?, ?)"
                        (java.util.UUID/randomUUID)
                        (java.util.UUID/randomUUID)
                        (->pgobject event-data)
                        occurred-at
                        (java.util.UUID/randomUUID)
                        (str (java.util.UUID/randomUUID))])))

;; =============================================================================
;; company-by-registration-number
;; =============================================================================

(deftest company-by-reg-number-returns-nil-when-not-found
  (let [adapter (sut/make-postgres-register-adapter *ds*)]
    (is (nil? (register-port/company-by-registration-number adapter "DOES-NOT-EXIST")))))

(deftest company-by-reg-number-returns-company-when-found
  (insert-company! *ds* :registration-number "REG-001" :company-name "Acme Ltd")
  (let [adapter  (sut/make-postgres-register-adapter *ds*)
        company  (register-port/company-by-registration-number adapter "REG-001")]
    (is (some? company))
    (is (= "REG-001" (:register/registration_number company)))
    (is (= "Acme Ltd" (:register/company_name company)))
    (is (some? (:register/registered_at company)))))

;; =============================================================================
;; search-by-name
;; =============================================================================

(deftest search-returns-empty-when-no-match
  (insert-company! *ds* :company-name "Acme Ltd")
  (let [adapter (sut/make-postgres-register-adapter *ds*)]
    (is (empty? (register-port/search-by-name adapter "Unrelated")))))

(deftest search-finds-case-insensitive-match
  (insert-company! *ds* :company-name "Acme Ltd")
  (let [adapter  (sut/make-postgres-register-adapter *ds*)
        results  (register-port/search-by-name adapter "acme")]
    (is (= 1 (count results)))
    (is (= "Acme Ltd" (:register/company_name (first results))))))

(deftest search-finds-partial-match
  (insert-company! *ds* :registration-number "REG-001" :company-name "Acme Holdings Ltd")
  (insert-company! *ds* :registration-number "REG-002" :company-name "Acme Services Ltd")
  (insert-company! *ds* :registration-number "REG-003" :company-name "Beta Corp")
  (let [adapter (sut/make-postgres-register-adapter *ds*)
        results (register-port/search-by-name adapter "Acme")]
    (is (= 2 (count results)))
    (is (every? #(str/starts-with? (:register/company_name %) "Acme") results))))

(deftest search-returns-required-fields
  (insert-company! *ds* :registration-number "REG-001" :company-name "Acme Ltd")
  (let [adapter (sut/make-postgres-register-adapter *ds*)
        result  (first (register-port/search-by-name adapter "Acme"))]
    (is (some? (:register/registration_number result)))
    (is (some? (:register/company_name result)))
    (is (some? (:register/registered_at result)))))
