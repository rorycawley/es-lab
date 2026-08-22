(ns lab26.platform.behaviour
  "Cross-cutting request behaviours shared without sharing business logic.

  A slice owns its schema and handler. Validation, telemetry and observation
  wrap that handler at the module API, so no concern is repeated inside every
  use case. A relational transaction is deliberately not generic here: each
  command slice opens the transaction around the tables it owns.

  Lab 25 established this shape with an `observation` that put a keyword in an
  atom. That was a placeholder in the shape of the real thing, and `telemetry`
  below is the real thing arriving. The atom stays, because a test asserting an
  in-process outcome should not have to read a span."
  (:require [lab26.platform.telemetry :as telemetry]
            [malli.core :as m]
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

(defn telemetry
  "Build a behaviour that gives one request a span, a log line and a count.

  `options` is passed to `telemetry/observe`, less `:attributes`, which is a
  function of the request. It has to be a function — the attributes worth
  exporting differ per slice — and it has to be written out per slice rather
  than derived from the request, because a derived allow-list is not one."
  [request-name {:keys [attributes parent] :as options}]
  (fn [handler]
    (fn [request]
      (telemetry/observe (assoc (dissoc options :attributes)
                                :name       request-name
                                :parent     (when parent (parent request))
                                :attributes (if attributes (attributes request) {}))
                         #(handler request)))))

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
