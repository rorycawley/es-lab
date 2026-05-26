(ns registry.registration.adapters.postgres-register
  (:require [next.jdbc                               :as jdbc]
            [next.jdbc.result-set                    :as rs]
            [registry.registration.ports.register-port :as port]))

(defn- register-row [row]
  (when row
    {:register/registration_number       (:registration_number row)
     :register/company_name              (:company_name row)
     :register/registered_office_address (:registered_office_address row)
     :register/registered_at             (:registered_at row)}))

(defrecord PostgresRegisterAdapter [datasource]
  port/RegisterPort

  (company-by-registration-number [_ registration-number]
    (register-row
     (jdbc/execute-one!
      datasource
      ["SELECT event_data ->> 'registration/number' AS registration_number,
               event_data ->> 'company/name' AS company_name,
               event_data -> 'registered-office-address' AS registered_office_address,
               occurred_at AS registered_at
        FROM   registration_events
        WHERE  event_type = 'company-registered'
        AND    event_data ->> 'registration/number' = ?
        LIMIT  1" registration-number]
      {:builder-fn rs/as-unqualified-lower-maps})))

  (search-by-name [_ name-fragment]
    (mapv register-row
          (jdbc/execute!
           datasource
           ["SELECT event_data ->> 'registration/number' AS registration_number,
                    event_data ->> 'company/name' AS company_name,
                    event_data -> 'registered-office-address' AS registered_office_address,
                    occurred_at AS registered_at
             FROM   registration_events
             WHERE  event_type = 'company-registered'
             AND    event_data ->> 'company/name' ILIKE ?
             ORDER  BY event_data ->> 'company/name' ASC
             LIMIT  50" (str "%" name-fragment "%")]
           {:builder-fn rs/as-unqualified-lower-maps}))))

(defn make-postgres-register-adapter [datasource]
  (->PostgresRegisterAdapter datasource))
