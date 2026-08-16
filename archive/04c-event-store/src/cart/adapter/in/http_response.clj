(ns cart.adapter.in.http-response
  "Shared Ring response mapping for cart task adapters."
  (:require [cheshire.core :as json])
  (:import [java.util UUID]))

(def ^:private content-type "application/json; charset=utf-8")

(defn response [outcome]
  {:status (case (:outcome outcome)
             :success 200
             :invalid 400
             :conflict 409
             :rejected 422)
   :headers {"content-type" content-type}
   :body (json/generate-string outcome)})

(defn unexpected-response
  ([throwable] (unexpected-response throwable #(UUID/randomUUID)))
  ([_throwable correlation-id-fn]
   {:status 500
    :headers {"content-type" content-type}
    :body (json/generate-string
           {:outcome :error
            :code :internal-server-error
            :correlation-id (str (correlation-id-fn))})}))

(defn body-map [request]
  (if (contains? request :body-params)
    (:body-params request)
    (let [body (:body request)
          text (cond
                 (string? body) body
                 (nil? body) ""
                 :else (slurp body))]
      (json/parse-string text true))))
