(ns registry.registration.adapters.postgres-event-store-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [next.jdbc    :as jdbc]
            [registry.db.migrations                              :as migrations]
            [registry.registration.decider                       :as decider]
            [registry.registration.adapters.postgres-event-store :as sut]
            [registry.decider.runner                             :as runner])
  (:import [java.time Instant]
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
  (jdbc/execute! *ds* ["TRUNCATE TABLE registration_events"])
  (f))

(use-fixtures :once with-postgres)
(use-fixtures :each truncate)

(defn make-app-id [] (str (java.util.UUID/randomUUID)))

(defn sample-event [type]
  {:event/id             (java.util.UUID/randomUUID)
   :event/type           type
   :event/causation-id   (java.util.UUID/randomUUID)
   :event/correlation-id (java.util.UUID/randomUUID)
   :application/id       (make-app-id)
   :applicant/id         "applicant-001"
   :occurred-at          (Instant/now)})

(def verified-director-1
  {:id "dir-001" :name "Jane Smith" :natural-person? true :identity-verified? true})

(def verified-director-2
  {:id "dir-002" :name "Alice Jones" :natural-person? true :identity-verified? true})

(def valid-office
  {:address-line-1 "1 Main Street" :city "Dublin" :country "IE"})

(defn- create-under-examination! [store company-name]
  (let [created (runner/create! decider/registration-decider
                                store
                                {:command/type :create-registration-application
                                 :applicant-id  "applicant-001"}
                                nil)
        app-id  (:aggregate-id created)]
    (when-not (:ok created)
      (throw (ex-info "Failed to create application" created)))
    (doseq [result [(runner/execute! decider/registration-decider
                                     store
                                     app-id
                                     {:command/type              :submit-registration-application
                                      :company-name              company-name
                                      :proposed-directors        [verified-director-1 verified-director-2]
                                      :registered-office-address valid-office}
                                     nil)
                    (runner/execute! decider/registration-decider
                                     store
                                     app-id
                                     {:command/type :begin-examination
                                      :examiner-id   "examiner-001"}
                                     nil)]]
      (when-not (:ok result)
        (throw (ex-info "Failed to prepare application" result))))
    app-id))

(defn- approve! [store app-id]
  (runner/execute! decider/registration-decider
                   store
                   app-id
                   {:command/type    :approve-registration-application
                    :registrar-id     "registrar-001"
                    :address-valid-fn (constantly true)}
                   nil))

(deftest load-events-returns-empty-for-unknown-aggregate
  (let [store (sut/make-postgres-event-store *ds*)]
    (is (= [] (runner/load-events store (make-app-id))))))

(deftest save-and-load-roundtrip
  (let [store  (sut/make-postgres-event-store *ds*)
        app-id (make-app-id)
        event  (sample-event :registration-application-created)]
    (runner/save-events! store app-id [event])
    (let [loaded (runner/load-events store app-id)]
      (is (= 1 (count loaded)))
      (is (= :registration-application-created (:event/type (first loaded)))))))

(deftest events-loaded-in-sequence-order
  (let [store  (sut/make-postgres-event-store *ds*)
        app-id (make-app-id)
        events [(sample-event :registration-application-created)
                (sample-event :registration-application-submitted)
                (sample-event :examination-started)]]
    (runner/save-events! store app-id events)
    (let [loaded (runner/load-events store app-id)]
      (is (= 3 (count loaded)))
      (is (= [:registration-application-created
              :registration-application-submitted
              :examination-started]
             (mapv :event/type loaded))))))

(deftest events-isolated-by-aggregate
  (let [store    (sut/make-postgres-event-store *ds*)
        app-id-1 (make-app-id)
        app-id-2 (make-app-id)]
    (runner/save-events! store app-id-1 [(sample-event :registration-application-created)])
    (runner/save-events! store app-id-2 [(sample-event :registration-application-created)
                                         (sample-event :registration-application-submitted)])
    (is (= 1 (count (runner/load-events store app-id-1))))
    (is (= 2 (count (runner/load-events store app-id-2))))))

(deftest duplicate-event-id-throws
  (let [store  (sut/make-postgres-event-store *ds*)
        app-id (make-app-id)
        event  (sample-event :registration-application-created)]
    (runner/save-events! store app-id [event])
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Duplicate event"
         (runner/save-events! store app-id [event])))))

(deftest approval-persists-both-events-from-one-command
  (let [store   (sut/make-postgres-event-store *ds*)
        created (runner/create! decider/registration-decider
                                store
                                {:command/type :create-registration-application
                                 :applicant-id  "applicant-001"}
                                nil)
        app-id  (:aggregate-id created)]
    (is (:ok created))

    (is (:ok (runner/execute! decider/registration-decider
                              store
                              app-id
                              {:command/type              :submit-registration-application
                               :company-name              "Acme Ltd"
                               :proposed-directors        [verified-director-1 verified-director-2]
                               :registered-office-address valid-office}
                              nil)))
    (is (:ok (runner/execute! decider/registration-decider
                              store
                              app-id
                              {:command/type :begin-examination
                               :examiner-id   "examiner-001"}
                              nil)))

    (let [approved (runner/execute! decider/registration-decider
                                    store
                                    app-id
                                    {:command/type   :approve-registration-application
                                     :registrar-id    "registrar-001"
                                     :address-valid-fn (constantly true)}
                                    nil)
          events   (runner/load-events store app-id)
          approval-events (filterv #(contains? #{:registration-application-approved
                                                 :company-registered}
                                               (:event/type %))
                                   events)]
      (is (:ok approved))
      (is (not (:idempotent approved)))
      (is (= 5 (count events)))
      (is (= [:registration-application-approved :company-registered]
             (mapv :event/type approval-events)))
      (is (every? :event/causation-id approval-events))
      (is (= 1 (count (distinct (map :event/causation-id approval-events))))))))

(deftest duplicate-company-name-is-rejected-by-event-store
  (let [store    (sut/make-postgres-event-store *ds*)
        app-id-1 (create-under-examination! store "Acme Ltd")
        app-id-2 (create-under-examination! store "Acme Ltd")]
    (is (:ok (approve! store app-id-1)))
    (is (= {:error :company-name-already-exists-in-register}
           (approve! store app-id-2)))
    (is (= [:registration-application-created
            :registration-application-submitted
            :examination-started]
           (mapv :event/type (runner/load-events store app-id-2))))
    (is (= 1
           (count
            (filter #(= :company-registered (:event/type %))
                    (mapcat #(runner/load-events store %) [app-id-1 app-id-2])))))))
