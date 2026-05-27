(ns registry.draft.api.handlers)

(defn create-draft [_request]
  (let [draft-id (str (java.util.UUID/randomUUID))]
    {:status  201
     :headers {"Location" (str "/api/v1/company-registration-drafts/" draft-id)}
     :body    {:draft-id draft-id
               :state    "active"}}))
