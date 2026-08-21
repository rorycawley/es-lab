-- The event store, as REFERENCE.md describes it.
--
-- Every column here is argued for somewhere in that document; this file is
-- where those arguments become a schema.

-- A unique stream-version constraint catches stale writers, but not a caller
-- claiming a version ahead of reality. This row is the compare-and-set token.
CREATE TABLE IF NOT EXISTS stream_head (
  stream_id       UUID   PRIMARY KEY,
  stream_version  BIGINT NOT NULL,
  CONSTRAINT stream_head_version_non_negative CHECK (stream_version >= 0)
);

CREATE TABLE IF NOT EXISTS event (
  -- Assigned by one database authority across concurrent writers. Sequence
  -- gaps after rollback are valid.
  global_position BIGSERIAL   PRIMARY KEY,

  -- The transaction that wrote this row. Not domain data — it is what lets a
  -- reader tell "committed" from "assigned but still in flight".
  xid             xid8        NOT NULL DEFAULT pg_current_xact_id(),

  -- Minted by the application, before the write (lab 4). UNIQUE detects a
  -- repetition; the adapter verifies an exact retry before returning it.
  event_id        UUID        NOT NULL,

  event_type      TEXT        NOT NULL,

  -- Whose history, and where in it (lab 7).
  stream_id       UUID        NOT NULL,
  stream_version  BIGINT      NOT NULL,

  -- When it happened, from the application (lab 1).
  occurred_at     TIMESTAMPTZ NOT NULL,

  -- Database transaction time, deliberately distinct from occurred_at.
  recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

  data            JSONB       NOT NULL,
  metadata        JSONB       NOT NULL DEFAULT '{}'::jsonb,

  CONSTRAINT event_id_unique UNIQUE (event_id),
  CONSTRAINT stream_version_unique UNIQUE (stream_id, stream_version),

  CONSTRAINT stream_version_positive CHECK (stream_version > 0),
  CONSTRAINT data_is_object CHECK (jsonb_typeof(data) = 'object'),
  CONSTRAINT metadata_is_object CHECK (jsonb_typeof(metadata) = 'object')
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
  command_id      UUID        NOT NULL PRIMARY KEY,
  stream_id       UUID        NOT NULL,
  command_type    TEXT        NOT NULL,
  correlation_id  UUID        NOT NULL,
  command_data    JSONB       NOT NULL,
  event_count     INTEGER     NOT NULL,
  handled_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

  CONSTRAINT event_count_not_negative CHECK (event_count >= 0),
  CONSTRAINT command_data_is_object CHECK (jsonb_typeof(command_data) = 'object')
);

-- A representative local consumer effect. Keeping it out of the event table
-- prevents the example consumer from bypassing aggregate stream invariants.
CREATE TABLE IF NOT EXISTS customer_notification (
  fact_id      UUID        PRIMARY KEY,
  flavour     TEXT        NOT NULL,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
