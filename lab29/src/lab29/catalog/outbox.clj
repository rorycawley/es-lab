(ns lab29.catalog.outbox
  "Catalog's outbox and delivery records.

  The tables are this module's; the shape and the machinery are the
  platform's. See `platform/outbox.clj` for why an outbox stopped having
  typed columns the moment a module had two kinds of message to send."
  (:require [lab29.platform.delivery :as delivery]
            [lab29.platform.outbox :as outbox]))

(def schema "catalog")

(defn enqueue!
  "Record an outgoing message inside the caller's transaction."
  [tx message]
  (outbox/enqueue! tx schema message))

(defn dead-letters [{:keys [datasource]}]
  (delivery/dead-letters datasource schema))

(defn revive! [{:keys [datasource]} message-id consumer]
  (delivery/revive! datasource schema message-id consumer))
