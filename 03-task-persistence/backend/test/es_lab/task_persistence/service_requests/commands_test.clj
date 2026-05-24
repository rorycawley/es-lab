(ns es-lab.task-persistence.service-requests.commands-test
  (:require [clojure.test :refer [deftest is]]
            [es-lab.task-persistence.service-requests.commands :as commands]
            [es-lab.task-persistence.test-support :as ts]))

(defn- make-request
  ([body] (make-request body {}))
  ([body headers] {:headers headers :body-params body}))

(deftest submit-returns-201-with-correct-fields
  ;; AC-04-01
  (let [ctx     (ts/make-ports)
        handler (commands/submit-service-request-handler ctx)
        resp    (handler (make-request {:title "T" :description "D"}))]
    (is (= 201 (:status resp)))
    (let [body (:body resp)]
      (is (= #{:request_id :title :description :status :submitted_by :created_at :updated_at}
             (set (keys body))))
      (is (= "T" (:title body)))
      (is (= "D" (:description body)))
      (is (= "submitted" (:status body)))
      (is (string? (:request_id body)))
      (is (string? (:submitted_by body)))
      (is (string? (:created_at body)))
      (is (string? (:updated_at body))))))

(deftest submit-reads-x-user-id-header
  ;; AC-04-02
  (let [ctx     (ts/make-ports)
        handler (commands/submit-service-request-handler ctx)
        resp    (handler (make-request {:title "T" :description "D"} {"x-user-id" "alice"}))]
    (is (= "alice" (get-in resp [:body :submitted_by])))))

(deftest submit-defaults-submitted-by-to-demo-user
  ;; AC-04-03
  (let [ctx     (ts/make-ports)
        handler (commands/submit-service-request-handler ctx)
        resp    (handler (make-request {:title "T" :description "D"}))]
    (is (= "demo-user" (get-in resp [:body :submitted_by])))))

(deftest submit-rejects-missing-title
  ;; AC-04-04
  (let [ctx     (ts/make-ports)
        handler (commands/submit-service-request-handler ctx)]
    (is (= 422 (:status (handler (make-request {:description "D"})))))))

(deftest submit-rejects-blank-title
  ;; AC-04-04
  (let [ctx     (ts/make-ports)
        handler (commands/submit-service-request-handler ctx)]
    (doseq [blank ["" "   "]]
      (is (= 422 (:status (handler (make-request {:title blank :description "D"}))))))))

(deftest submit-rejects-missing-description
  ;; AC-04-05
  (let [ctx     (ts/make-ports)
        handler (commands/submit-service-request-handler ctx)]
    (is (= 422 (:status (handler (make-request {:title "T"})))))))

(deftest submit-rejects-blank-description
  ;; AC-04-05
  (let [ctx     (ts/make-ports)
        handler (commands/submit-service-request-handler ctx)]
    (doseq [blank ["" "   "]]
      (is (= 422 (:status (handler (make-request {:title "T" :description blank}))))))))

(deftest submit-writes-audit-event-with-correct-fields
  ;; QA-03-01
  (let [{:keys [audit-port] :as ctx} (ts/make-ports)
        handler (commands/submit-service-request-handler ctx)
        resp    (handler (make-request {:title "T" :description "D"} {"x-user-id" "alice"}))
        events  @(:!store audit-port)]
    (is (= 1 (count events)))
    (let [event (first events)]
      (is (= "alice" (:actor event)))
      (is (= "submit-service-request" (:action event)))
      (is (= (get-in resp [:body :request_id]) (:subject-id event))))))

(deftest submit-audit-defaults-actor-to-demo-user
  ;; QA-03-02
  (let [{:keys [audit-port] :as ctx} (ts/make-ports)
        handler (commands/submit-service-request-handler ctx)
        _       (handler (make-request {:title "T" :description "D"}))
        events  @(:!store audit-port)]
    (is (= "demo-user" (:actor (first events))))))

(deftest two-submits-produce-two-audit-events
  ;; QA-03-04
  (let [{:keys [audit-port] :as ctx} (ts/make-ports)
        handler (commands/submit-service-request-handler ctx)]
    (handler (make-request {:title "A" :description "First"}))
    (handler (make-request {:title "B" :description "Second"}))
    (is (= 2 (count @(:!store audit-port))))))
