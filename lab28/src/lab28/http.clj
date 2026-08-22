(ns lab28.http
  "The driving adapter for inbound provider callbacks.

  Lab 23's rule holds: reitit, ring and jetty appear here and in `system.clj`
  and nowhere else, and `architecture_test.clj` fails the build if they spread.

  The order of the four steps below is the whole design, and each one exists
  because getting it wrong has a specific consequence:

      1. read the raw body      a signature is over bytes, not over a map
      2. verify the signature   before parsing, before believing anything
      3. translate              the adapter's ACL, provider words stop here
      4. hand to the module     domain shape only

  ## What status code to answer

  Providers retry on any non-2xx, forever, with backoff. That turns the status
  code into a control signal rather than a report:

      200  understood, or already seen, or not subscribed to
           -- anything a retry cannot improve
      400  the signature did not verify
           -- a retry cannot improve it either, and 200 would tell an attacker
              nothing distinguishes a forged callback from a real one
      500  we could not process something we did understand
           -- the one case where please-send-it-again is the right answer

  The temptation is 500 on a duplicate, because a duplicate feels like an
  error. It is not: it is the delivery guarantee working."
  (:require [clojure.data.json :as json]
            [lab28.payments.adapter.stripe :as stripe]
            [lab28.payments.api :as payments]
            [reitit.ring :as ring])
  (:import (java.io InputStream)
           (java.nio.charset StandardCharsets)))

(defn- read-body ^String [request]
  (if-let [body (:body request)]
    (if (instance? InputStream body)
      (String. (.readAllBytes ^InputStream body) StandardCharsets/UTF_8)
      (str body))
    ""))

(defn- json-response [status body]
  {:status status
   :headers {"content-type" "application/json"}
   :body (json/write-str body)})

(defn stripe-webhook-handler
  "One endpoint, built from a verifier, a translator and a module."
  [{:keys [payments signing-secret now tolerance-seconds]
    :or   {tolerance-seconds 300}}]
  (fn [request]
    (let [body      (read-body request)
          signature (get-in request [:headers "stripe-signature"])
          verified  (stripe/verify-signature signing-secret signature body
                                             (now) tolerance-seconds)]
      (if-not (:valid? verified)
        (json-response 400 {:error (name (:because verified))})
        ;; A body we cannot translate is the one case worth a 500.
        ;;
        ;; It means the provider sent a type we *did* subscribe to, in a shape
        ;; we do not understand -- a format change, most likely. Answering 2xx
        ;; discards it silently. Answering 500 makes the provider hold it and
        ;; keep offering it for the next few days, which is exactly long enough
        ;; to notice and ship a fix. That is the difference between a retry
        ;; being useless and a retry being the whole point.
        (try
          (let [event  (stripe/translate-event (json/read-str body))
                result (payments/callback! payments event)]
            (json-response 200 (cond
                                 (:accepted result)        {:received true :applied true}
                                 (:duplicate result)       {:received true :duplicate true}
                                 (:ignored result)         {:received true :ignored true}
                                 (:already-applied result) {:received true :already-applied true}
                                 (:unmatched result)       {:received true :unmatched true}
                                 :else                     {:received true})))
          (catch clojure.lang.ExceptionInfo e
            (if (= :untranslatable-provider-event (:reason (ex-data e)))
              (json-response 500 {:error "untranslatable" :retry true})
              (throw e))))))))

(defn router
  [{:keys [payments] :as options}]
  (ring/ring-handler
   (ring/router
    [["/health" {:get (fn [_] (json-response 200 {:status "ok"
                                                  :gateway (payments/provider payments)}))}]
     ["/webhooks/stripe" {:post (stripe-webhook-handler options)}]])
   (ring/create-default-handler
    {:not-found (fn [_] (json-response 404 {:error "not-found"}))})))
