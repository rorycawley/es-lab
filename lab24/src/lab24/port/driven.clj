(ns lab24.port.driven
  "The driven/output ports: what the use cases ask the world for.

  Five protocols name capabilities needed at the application edge: event
  storage, an outbox, time, identifier allocation and OIDC verification keys.
  The core remains plain functions and values; the shell coordinates these
  effects around it.

  The driving/input ports are the callable use-case functions in `app.clj`.
  Tests and the demo drive them directly; `adapter/intake.clj` drives them
  after translating and validating an untrusted message.

  Output ports name required capabilities, not current vendors: `EventStore`,
  not `PostgresClient`. A domain-shaped repository could still be appropriate
  where the use case genuinely reasons in aggregates; the diagram alone does
  not require one protocol per entity.")

(defprotocol EventStore
  "Durable facts and the atomic command-outcome transaction."
  (command-result [this stream-id command]
    "The original recorded events for a handled command, or nil when unseen.")
  (commit-command [this stream-id expected-version command events messages]
    "Atomically append events, record command idempotency, and enqueue messages.")
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

(defprotocol VerificationKeys
  "Public keys supplied by the identity provider.

  The port deliberately supplies keys rather than an `authenticate` verdict:
  signature, issuer, audience and expiry verification remain decisions in the
  authentication adapter."
  (verification-key [this kid]
    "The public key published under `kid`, or nil when it is unknown."))
