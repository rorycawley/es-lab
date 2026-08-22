(ns lab29.fake-subscriber
  "A WebSub subscriber, on a real socket, outside the registry.

  It lives in `dev/` for the reason lab 24 gave about its identity provider
  and lab 28 about its payment provider: a counterparty is a dependency of
  your tests and never of your application.

  It is deliberately a *correct* subscriber, so that when a test makes it
  behave badly -- refusing verification, refusing delivery, echoing the wrong
  challenge -- the misbehaviour is the thing under test rather than an
  accident of a sloppy double."
  (:require [clojure.string :as str]
            [lab29.websub.signature :as signature]
            [ring.adapter.jetty :as jetty])
  (:import (java.io InputStream)
           (java.nio.charset StandardCharsets)))

(defn- query-params [request]
  (into {} (for [pair (str/split (or (:query-string request) "") #"&")
                 :let [[k v] (str/split pair #"=" 2)]
                 :when v]
             [(java.net.URLDecoder/decode k "UTF-8")
              (java.net.URLDecoder/decode v "UTF-8")])))

(defn- read-body [request]
  (if-let [body (:body request)]
    (String. (.readAllBytes ^InputStream body) StandardCharsets/UTF_8)
    ""))

(defn handler
  "`state` holds `{:secret .. :verify? .. :accept? .. :received [] :verifications []}`."
  [state]
  (fn [request]
    (let [params (query-params request)]
      (cond
        ;; Verification of intent. The hub is asking whether we really asked.
        (get params "hub.mode")
        (do (swap! state update :verifications conj
                   {:mode (get params "hub.mode") :topic (get params "hub.topic")})
            (if (:verify? @state)
              {:status 200 :headers {"content-type" "text/plain"}
               :body (get params "hub.challenge")}
              {:status 404 :headers {} :body "I did not ask for this"}))

        (= :post (:request-method request))
        (let [body      (read-body request)
              signature (get-in request [:headers "x-hub-signature"])
              genuine?  (signature/verify (:secret @state) signature body)]
          ;; A real subscriber checks the signature and ignores what does not
          ;; verify. Recording both lets a test assert we were signed
          ;; correctly rather than merely delivered to.
          (swap! state update :received conj
                 {:body body :signature signature :genuine? genuine?
                  :links (get-in request [:headers "link"])})
          (if (:accept? @state)
            {:status 204 :headers {} :body ""}
            {:status 500 :headers {} :body "not today"}))

        :else {:status 405 :headers {} :body ""}))))

(defn start!
  ([] (start! {}))
  ([overrides]
   (let [state  (atom (merge {:secret "s3cret" :verify? true :accept? true
                              :received [] :verifications []}
                             overrides))
         server (jetty/run-jetty (handler state) {:port 0 :join? false})
         port   (.getLocalPort (first (.getConnectors server)))]
     {:server server :port port :state state
      :callback (str "http://localhost:" port "/on-registry-change")
      :secret (:secret @state)})))

(defn stop! [{:keys [server]}] (.stop server))

(defn received [{:keys [state]}] (:received @state))
(defn verifications [{:keys [state]}] (:verifications @state))
(defn refuse-verification! [{:keys [state]}] (swap! state assoc :verify? false))
(defn refuse-delivery! [{:keys [state]}] (swap! state assoc :accept? false))
(defn accept-delivery! [{:keys [state]}] (swap! state assoc :accept? true))
