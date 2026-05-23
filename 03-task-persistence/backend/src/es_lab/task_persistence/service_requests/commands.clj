(ns es-lab.task-persistence.service-requests.commands
  (:require [clojure.string :as str]
            [es-lab.task-persistence.audit.port :as audit]
            [es-lab.task-persistence.service-requests.port :as sr]))

(defn- validate [body]
  (cond
    (not (string? (:title body)))       {:status 422 :body {:error "title must be a string"}}
    (str/blank? (:title body))          {:status 422 :body {:error "title cannot be blank"}}
    (not (string? (:description body))) {:status 422 :body {:error "description must be a string"}}
    (str/blank? (:description body))    {:status 422 :body {:error "description cannot be blank"}}
    :else nil))

(defn submit-service-request-handler [{:keys [service-request-port audit-port]}]
  (fn [request]
    (let [user-id (get-in request [:headers "x-user-id"] "demo-user")
          body    (:body-params request)]
      (if-let [err (validate body)]
        err
        (let [saved (sr/save! service-request-port
                              {:submitted-by user-id
                               :title        (:title body)
                               :description  (:description body)})]
          (audit/record! audit-port
                         {:actor      user-id
                          :action     "submit-service-request"
                          :subject-id (:request_id saved)
                          :metadata   {:title (:title body)}})
          {:status 200 :body saved})))))
