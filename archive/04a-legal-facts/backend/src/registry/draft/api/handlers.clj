(ns registry.draft.api.handlers)

(def ^:private drafts (atom {}))

(defn create-draft [_request]
  (let [draft-id (str (java.util.UUID/randomUUID))
        draft    {:draft-id draft-id :state "active"}]
    (swap! drafts assoc draft-id draft)
    {:status  201
     :headers {"Location" (str "/api/v1/company-registration-drafts/" draft-id)}
     :body    draft}))

(defn get-draft [request]
  (let [id    (get-in request [:path-params :id])
        draft (get @drafts id)]
    (if draft
      {:status 200 :body draft}
      {:status 404 :body {:error "not-found"}})))
