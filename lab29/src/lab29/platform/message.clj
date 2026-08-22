(ns lab29.platform.message
  "Envelopes, and the two constructors that keep them apart.

  There is no `message` constructor here, deliberately. A single generic one
  would let a caller write a message without deciding what kind of thing it is,
  and the kind is the only property that determines how many people should
  receive it. Two functions make the decision unavoidable and visible at the
  point it is actually made.

  Transport metadata stays out of the business data. Nothing here carries a
  retry count, a database id or a delivery attempt: those belong to the
  machinery that moves the message, not to the message.

  A command's business data is `:data` and an integration event's is
  `:payload`. That is lab 1's distinction and `bb audit` enforces it across the
  repository: a payload is a blob in transit, which is exactly what an
  integration event is and exactly what a command handed to its one destination
  is not. The messaging document uses `:data` for both; this repository has
  twenty-nine labs of the other convention and REFERENCE.md's rationale for it,
  so the divergence is deliberate."
  (:require [malli.core :as m]))

(def ^:private Metadata
  [:map {:closed true}
   [:causation-id :uuid]
   [:correlation-id :uuid]])

(def Command
  "A request that one module do something. Exactly one destination."
  [:map {:closed true}
   [:message/id :uuid]
   [:message/kind [:= :command]]
   [:command/type :qualified-keyword]
   [:metadata Metadata]
   [:data [:map]]])

(def IntegrationEvent
  "A fact one module chose to expose. Zero, one or many consumers."
  [:map {:closed true}
   [:message/id :uuid]
   [:message/kind [:= :integration-event]]
   [:event/type :qualified-keyword]
   [:metadata Metadata]
   [:payload [:map]]])

(defn command
  [message-id command-type {:keys [causation-id correlation-id]} data]
  {:message/id   message-id
   :message/kind :command
   :command/type command-type
   :metadata     {:causation-id causation-id :correlation-id correlation-id}
   :data         data})

(defn integration-event
  [message-id event-type {:keys [causation-id correlation-id]} payload]
  {:message/id   message-id
   :message/kind :integration-event
   :event/type   event-type
   :metadata     {:causation-id causation-id :correlation-id correlation-id}
   :payload      payload})

(defn body
  "The business content, whichever kind this is."
  [msg]
  (if (= :command (:message/kind msg)) (:data msg) (:payload msg)))

(defn message-type
  "The type, whichever kind this is. The only place the two are treated alike,
  because a routing table is indexed by both."
  [message]
  (case (:message/kind message)
    :command           (:command/type message)
    :integration-event (:event/type message)))

(defn command? [message] (= :command (:message/kind message)))
(defn event? [message] (= :integration-event (:message/kind message)))

(defn valid? [message]
  (m/validate (if (command? message) Command IntegrationEvent) message))

(defn explain [message]
  (m/explain (if (command? message) Command IntegrationEvent) message))
