(ns lab25.platform.behaviour
  "Cross-cutting request behaviours shared without sharing business logic.

  A slice owns its schema and handler. Validation and observation wrap that
  handler at the module API, so neither concern is repeated inside every use
  case. A relational transaction is deliberately not generic here: each
  command slice opens the transaction around the tables it owns."
  (:require [malli.core :as m]
            [malli.error :as me]))

(defn validation
  "Build a behaviour that rejects malformed input before calling `handler`."
  [schema]
  (fn [handler]
    (fn [request]
      (if (m/validate schema request)
        (handler request)
        {:rejected :malformed
         :because  (me/humanize (m/explain schema request))}))))

(defn observation
  "Build a behaviour that records one entry for every completed request."
  [audit request-name]
  (fn [handler]
    (fn [request]
      (let [response (handler request)]
        (swap! audit conj {:request request-name
                           :outcome (cond
                                      (:accepted response) :accepted
                                      (:found response)    :found
                                      (:rejected response) (:rejected response)
                                      :else                :completed)})
        response))))

(defn compose
  "Wrap `handler` with behaviours in the order they are listed."
  [handler behaviours]
  (reduce (fn [next behaviour] (behaviour next))
          handler
          (reverse behaviours)))
