(ns lab29.payments.outbox
  "Payments' outbox and delivery records.

  The tables are this module's; the shape and the machinery are the
  platform's. See `platform/outbox.clj` for why an outbox stopped having
  typed columns the moment a module had two kinds of message to send."
  (:require [lab29.platform.delivery :as delivery]
            [lab29.platform.outbox :as outbox]))

(def schema "payments")

(defn enqueue!
  "Record an outgoing message inside the caller's transaction."
  [tx message]
  (outbox/enqueue! tx schema message))

(defn enqueue-once!
  "Record a payment outcome whose deterministic id makes repeats equivalent."
  [tx message]
  (outbox/enqueue-once! tx schema message))

(defn dead-letters [{:keys [datasource]}]
  (delivery/dead-letters datasource schema))

(defn revive! [{:keys [datasource]} message-id consumer]
  (delivery/revive! datasource schema message-id consumer))
