(ns lab21.port
  "The driven/output ports: what the application asks the world for.

  Four protocols, and every one of them names something the *core* must never
  do — read, write, look at a clock, invent a number. Twenty labs argued those
  belong at the edge one at a time; this is where they become a boundary you
  can point at.

  The driving/input ports are the application's ordinary use-case functions:
  `app/handle`, `app/stock` and `app/react`. The demo and tests call those
  directly; lab 22 adds an intake adapter in front of them.

  These names describe capabilities the application needs, not the vendors
  supplying them. The event store appends and reads streams; Postgres is only
  one adapter that can do so.")

(defprotocol EventStore
  "Durable facts and the atomic command-outcome transaction."
  (command-result [this stream-id command]
    "The original recorded events for a handled command, or nil when unseen.
     Throws if the command id belongs to a different request.")
  (commit-command [this stream-id expected-version command events messages]
    "Atomically append identified `events`, record command idempotency and
     enqueue `messages`. Exact retries return the original recorded events;
     command-id collisions and version conflicts throw.")
  (read-stream [this stream-id]
    "One stream's history, oldest first.")
  (stream-version [this stream-id]
    "The version of the last event in a stream, or 0.")
  (read-since [this position]
    "Everything appended after `position`, across all streams."))

(defprotocol Outbox
  "The outgoing messages committed with command outcomes."
  (pending [this]
    "Messages not yet delivered."))

(defprotocol Clock
  "The current time, as an input rather than an ambient fact (lab 11)."
  (now [this]))

(defprotocol Ids
  "A fresh identifier, as an input rather than an ambient fact (lab 4)."
  (new-id [this]))
