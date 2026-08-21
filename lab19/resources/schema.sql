-- The event store, as REFERENCE.md describes it.
--
-- Every column here is argued for somewhere in that document; this file is
-- where those arguments become a schema.

-- The compare-and-set token. A UNIQUE event constraint rejects stale writers
-- but cannot reject a caller that claims a version ahead of the stream. This
-- row is updated conditionally inside the append transaction.
CREATE TABLE IF NOT EXISTS stream_head (
  stream_id       UUID   PRIMARY KEY,
  stream_version  BIGINT NOT NULL,

  CONSTRAINT stream_head_version_non_negative CHECK (stream_version >= 0)
);

CREATE TABLE IF NOT EXISTS event (
  -- Assigned by one database authority across concurrent writers. Exists for
  -- one job: letting a reader resume (lab 9). Gaps after rollback are valid.
  global_position BIGSERIAL   PRIMARY KEY,

  -- The transaction that wrote this row. Not domain data — it is what lets a
  -- reader tell "committed" from "assigned but still in flight".
  xid             xid8        NOT NULL DEFAULT pg_current_xact_id(),

  -- Minted by the application, before the write (lab 4), so a retry after an
  -- ambiguous failure carries the same id. UNIQUE detects repetition; the
  -- adapter verifies an exact retry before returning the original row.
  event_id        UUID        NOT NULL,

  event_type      TEXT        NOT NULL,

  -- Whose history, and where in it (lab 7).
  stream_id       UUID        NOT NULL,
  stream_version  BIGINT      NOT NULL,

  -- When it happened, from the application (lab 1).
  occurred_at     TIMESTAMPTZ NOT NULL,

  -- When the store wrote it down. `now()` is transaction-start and constant
  -- for the transaction, so every row in one append batch shares one value.
  recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

  data            JSONB       NOT NULL,
  metadata        JSONB       NOT NULL DEFAULT '{}'::jsonb,

  -- Defense-in-depth integrity constraints. The complete expected-version
  -- compare-and-set is the conditional stream_head update in `store/append`;
  -- uniqueness alone cannot reject a future expected version.
  CONSTRAINT event_id_unique UNIQUE (event_id),
  CONSTRAINT stream_version_unique UNIQUE (stream_id, stream_version),

  CONSTRAINT stream_version_positive CHECK (stream_version > 0),
  CONSTRAINT data_is_object CHECK (jsonb_typeof(data) = 'object'),
  CONSTRAINT metadata_is_object CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX IF NOT EXISTS event_stream_idx ON event (stream_id, stream_version);
