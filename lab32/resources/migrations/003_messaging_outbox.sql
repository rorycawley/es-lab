-- The outbox is a queue, not an archive.
--
-- That distinction is easy to lose, because this table and
-- `accounts.event_stream` hold nearly the same bytes. They have opposite
-- lifetimes: the stream is the permanent record and this is a work list that
-- is pruned at 24 hours (§7). If you ever find yourself querying the outbox to
-- answer a question about the business, the answer you want is in the stream.

SET ROLE messaging_module;

CREATE TYPE messaging.msg_status AS ENUM ('PENDING', 'PROCESSED', 'FAILED');

CREATE TABLE messaging.outbox (
  seq             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id        UUID        NOT NULL UNIQUE,
  source_module   TEXT        NOT NULL,
  event_type      TEXT        NOT NULL,
  -- Usually the aggregate id. Phase 3 claims whole partitions under an
  -- advisory lock to get per-aggregate ordering; until then this column is
  -- carried and not used, which is why Phase 1 makes no ordering claim.
  partition_key   TEXT        NOT NULL,
  payload         JSONB       NOT NULL,
  metadata        JSONB       NOT NULL DEFAULT '{}'::jsonb,
  status          messaging.msg_status NOT NULL DEFAULT 'PENDING',
  attempts        INT         NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_error      TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at    TIMESTAMPTZ
);

-- A PARTIAL index, and the `WHERE` clause is the point.
--
-- Without it the pending scan reads an index that grows with everything the
-- system has ever published, to find the handful of rows that still need work.
-- With it, the index contains only rows that are actually pending -- so it
-- stays small no matter how much traffic has been through, and a PROCESSED row
-- leaves the index the moment it is marked. `FAILED` rows fall out of it too,
-- which is what makes the dead-letter state cost nothing to carry.
CREATE INDEX idx_outbox_pending
  ON messaging.outbox (partition_key, seq)
  WHERE status = 'PENDING';

-- A queue table is mostly dead tuples. The default autovacuum threshold waits
-- for 20% of the table to be garbage, which on a table this churny means the
-- index bloats between runs.
ALTER TABLE messaging.outbox SET (autovacuum_vacuum_scale_factor = 0.01);

RESET ROLE;

-- Accounts may put a message in, and may not look in the queue or take
-- anything out of it. That is the whole of a producer's interest in an outbox,
-- and `SELECT` is deliberately not granted: a module that can read the
-- transport can be tempted to poll it instead of receiving its own events.
GRANT USAGE  ON SCHEMA messaging   TO accounts_module;
GRANT INSERT ON messaging.outbox   TO accounts_module;

-- One column, for the same reason migration 004 grants one on the inbox.
-- `ON CONFLICT (event_id) DO NOTHING` needs SELECT on the arbiter index's
-- columns, and a replay re-publishes facts whose ids may still be sitting in
-- this table. The producer may check the id it is inserting and may not read a
-- payload, a status, or anything else it wrote.
GRANT SELECT (event_id) ON messaging.outbox TO accounts_module;

-- Compliance needs the schema only to name the enum type its own inbox column
-- is declared with. It gets no rights on the outbox at all.
GRANT USAGE  ON SCHEMA messaging   TO compliance_module;
