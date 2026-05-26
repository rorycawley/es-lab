(ns registry.draft.api.handlers)

(defn create-draft [_request]
  {:status 201
   :body   {:draft-id (str (java.util.UUID/randomUUID))}})
