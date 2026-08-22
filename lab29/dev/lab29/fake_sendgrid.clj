(ns lab29.fake-sendgrid
  "A fake SendGrid, on a real socket.

  Its most important property is what it refuses to do: **it does not
  deduplicate.** Send the same notification id twice and it accepts twice and
  records twice, because that is what SendGrid does. Building a helpful fake
  here would hide the one fact this module exists to teach."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [ring.adapter.jetty :as jetty])
  (:import (java.io InputStream)
           (java.nio.charset StandardCharsets)))

(defn- read-body [request]
  (if-let [body (:body request)]
    (String. (.readAllBytes ^InputStream body) StandardCharsets/UTF_8)
    ""))

(defn handler
  "`state` is an atom holding `{:sent [] :fail-with nil}`."
  [state]
  (fn [request]
    (let [body (json/read-str (read-body request))
          to   (get-in body ["personalizations" 0 "to" 0 "email"])
          nid  (get-in body ["personalizations" 0 "custom_args" "notification_id"])]
      (cond
        (not (str/starts-with? (str (get-in request [:headers "authorization"])) "Bearer SG."))
        {:status 401 :headers {} :body (json/write-str {"errors" [{"message" "unauthorized"}]})}

        (pos? (:failures-left @state 0))
        (let [status (:fail-with @state)]
          (swap! state update :failures-left dec)
          {:status status :headers {}
           :body (json/write-str {"errors" [{"message" "try later"}]})})

        (str/ends-with? (str to) "@invalid.test")
        {:status 400 :headers {}
         :body (json/write-str {"errors" [{"message" "Does not contain a valid address."}]})}

        :else
        (let [reference (str "sg_" (random-uuid))]
          ;; Appended, never deduplicated. See the namespace docstring.
          (swap! state update :sent conj {:to to :notification-id nid :reference reference})
          {:status 202
           :headers {"X-Message-Id" reference}
           :body ""})))))

(defn start! []
  (let [state  (atom {:sent [] :fail-with nil :failures-left 0})
        server (jetty/run-jetty (handler state) {:port 0 :join? false})
        port   (.getLocalPort (first (.getConnectors server)))]
    {:server server :port port :base-url (str "http://localhost:" port) :state state}))

(defn stop! [{:keys [server]}] (.stop server))

(defn sent [{:keys [state]}] (:sent @state))

(defn fail-times!
  "Make the next `n` requests answer `status`.

  `n` matters: one failure is what a retry policy is for, and enough of them
  in a row is what a circuit breaker is for. A fake that can only fail once
  can only test half of that."
  [{:keys [state]} n status]
  (swap! state assoc :fail-with status :failures-left n))

(defn fail-next! [provider status] (fail-times! provider 1 status))
