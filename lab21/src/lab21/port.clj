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
  "Somewhere durable to put facts and get them back."
  (append [this stream-id expected-version command events]
    "Append `events` to `stream-id` if it is still at `expected-version`.
     Throws on conflict. Returns the events as recorded.")
  (read-stream [this stream-id]
    "One stream's history, oldest first.")
  (stream-version [this stream-id]
    "The version of the last event in a stream, or 0.")
  (read-since [this position]
    "Everything appended after `position`, across all streams."))

(defprotocol Outbox
  "Somewhere to leave a message for another module."
  (enqueue [this messages]
    "Record outgoing messages. Called inside the store's transaction.")
  (pending [this]
    "Messages not yet delivered."))

(defprotocol Clock
  "The current time, as an input rather than an ambient fact (lab 11)."
  (now [this]))

(defprotocol Ids
  "A fresh identifier, as an input rather than an ambient fact (lab 4)."
  (new-id [this]))
