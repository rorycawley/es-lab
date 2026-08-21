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
