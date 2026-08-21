(ns lab20.outbox
  "The producer's side: enqueue a message in the same transaction as the fact.

  Lab 12 made this argument against an in-memory vector, where a transaction
  was free and the failure it warns about could not occur. Here it can."
  (:require [clojure.data.json :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (org.postgresql.util PGobject)))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn- ->jsonb [x]
  (doto (PGobject.) (.setType "jsonb") (.setValue (json/write-str x))))

(defn- <-jsonb [^PGobject o]
  (when o (json/read-str (.getValue o) :key-fn keyword)))

(defn enqueue!
  "Write one outgoing message. Call inside the caller's transaction.

  `message-id` identifies this envelope. Command-ledger idempotency prevents
  an ambiguous command retry from creating another envelope; the payload's
  *fact id* is what recipients use to recognise a republished fact."
  [tx {:keys [message-id message-type recipient payload]}]
  (jdbc/execute-one!
   tx
   ["INSERT INTO outbox (message_id, message_type, recipient, payload)
     VALUES (?,?,?,?) RETURNING *"
    message-id (name message-type) (name recipient) (->jsonb payload)]
   opts))

(defn pending
  "Messages not yet marked sent, oldest first."
  [ds]
  (mapv #(update % :payload <-jsonb)
        (jdbc/execute! ds ["SELECT * FROM outbox WHERE sent_at IS NULL ORDER BY id"]
                       opts)))

(defn mark-sent!
  "The second write — and the one that can fail on its own."
  [tx id]
  (jdbc/execute-one! tx ["UPDATE outbox SET sent_at = now()
                          WHERE id = ? AND sent_at IS NULL RETURNING id" id] opts))

(defn all
  [ds]
  (mapv #(update % :payload <-jsonb)
        (jdbc/execute! ds ["SELECT * FROM outbox ORDER BY id"] opts)))
