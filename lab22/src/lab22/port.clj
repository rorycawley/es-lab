(ns lab22.port
  "The driven/output ports: what the use cases ask the world for.

  Four protocols name capabilities needed by the application but supplied at
  its edge: event storage, an outbox, time and identifier allocation. The core
  remains plain functions and values; the application coordinates these
  effects around it.

  The driving/input ports are the callable use-case functions in `app.clj`.
  Tests and the demo drive them directly; `adapter/intake.clj` drives them
  after translating and validating an untrusted message.

  Output ports name required capabilities, not current vendors: `EventStore`,
  not `PostgresClient`. A domain-shaped repository could still be appropriate
  where the use case genuinely reasons in aggregates; the diagram alone does
  not require one protocol per entity.")

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
