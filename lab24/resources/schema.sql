-- The event store, as REFERENCE.md describes it.
--
-- Every column here is argued for somewhere in that document; this file is
-- where those arguments become a schema.

CREATE TABLE IF NOT EXISTS event (
  -- Assigned by the single writer. Orders the whole log across streams, and
  -- exists for exactly one job: letting a reader resume (lab 9).
  global_position BIGSERIAL   PRIMARY KEY,

  -- The transaction that wrote this row. Not domain data — it is what lets a
  -- reader tell "committed" from "assigned but still in flight".
  xid             xid8        NOT NULL DEFAULT pg_current_xact_id(),

  -- Minted by the application, before the write (lab 4), so a retry after an
  -- ambiguous failure carries the same id and the UNIQUE makes it idempotent.
  event_id        UUID        NOT NULL UNIQUE,

  event_type      TEXT        NOT NULL,

  -- Whose history, and where in it (lab 7).
  stream_id       UUID        NOT NULL,
  stream_version  BIGINT      NOT NULL,

  -- When it happened, from the application (lab 1).
  occurred_at     TIMESTAMPTZ NOT NULL,

  -- When the store wrote it down. `now()` is transaction-start and constant
  -- for the transaction, so a batch shares one value — which is truthful,
  -- because they committed together.
  recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

  data            JSONB       NOT NULL,
  metadata        JSONB       NOT NULL DEFAULT '{}'::jsonb,

  -- Optimistic concurrency (lab 7), enforced by the database rather than by
  -- the application reading and hoping.
  UNIQUE (stream_id, stream_version),

  CONSTRAINT stream_version_positive CHECK (stream_version > 0),
  CONSTRAINT data_is_object CHECK (jsonb_typeof(data) = 'object')
);

CREATE INDEX IF NOT EXISTS event_stream_idx ON event (stream_id, stream_version);

-- ---------------------------------------------------------------------------
-- The outbox.
--
-- Lab 12 argued that an event log already is one, and that holds when the
-- message is derivable from an event. This table is for when it isn't: a
-- command addressed to another module, a message with its own retention, or
-- anything whose lifecycle is not the fact's.
--
-- The row is written in the same transaction as the event (ADR-0005), so
-- there is one write, not two, and no crash can separate them.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS outbox (
  id            BIGSERIAL   PRIMARY KEY,
  message_id    UUID        NOT NULL UNIQUE,
  message_type  TEXT        NOT NULL,
  recipient     TEXT        NOT NULL,
  payload       JSONB       NOT NULL,
  enqueued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- Marking a row sent is a SECOND write, and it can fail on its own.
  -- That is why at-least-once survives the outbox.
  sent_at       TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS outbox_pending_idx ON outbox (id) WHERE sent_at IS NULL;

-- ---------------------------------------------------------------------------
-- The inbox.
--
-- The consumer's side of the same problem. A record here is written in the
-- same transaction as the effect, so "I have handled this" and "I did the
-- thing" commit together or not at all.
--
-- Keyed by the FACT's id, not the delivery's (lab 4): a republished message
-- arrives in a new envelope and must still be recognised.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS inbox (
  recipient    TEXT        NOT NULL,
  fact_id      UUID        NOT NULL,
  handled_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

  -- Keyed by BOTH. One fact is delivered to several modules, and each must
  -- handle it once — "already handled" is a question about a recipient, not
  -- about the fact.
  PRIMARY KEY (recipient, fact_id)
);

-- ---------------------------------------------------------------------------
-- The command ledger (ADR-0004).
--
-- Lab 10 deduplicated by asking whether any event carried this command's
-- causation id. That works only while every command produces at least one
-- event — and lab 5 established that producing none is a legitimate outcome.
--
-- A ledger keyed by command id has no such hole: the row is written whether
-- the command produced three events, one, or none at all.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS command_ledger (
  command_id   UUID        NOT NULL PRIMARY KEY,
  command_type TEXT        NOT NULL,
  event_count  INTEGER     NOT NULL,
  handled_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

  CONSTRAINT event_count_not_negative CHECK (event_count >= 0)
);
