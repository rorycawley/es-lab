-- The permanent audit record. Nothing is ever deleted from this table.
--
-- Everything else in this lab is a queue with a retention policy. This is the
-- one table that is not, and the difference is the whole "why not a broker?"
-- argument: a topic with 24-hour retention cannot answer a question somebody
-- thinks of next year, and this can.

SET ROLE accounts_module;

CREATE TABLE accounts.event_stream (
  seq            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id       UUID        NOT NULL UNIQUE,
  aggregate_id   UUID        NOT NULL,
  aggregate_type TEXT        NOT NULL,
  version        INT         NOT NULL,
  event_type     TEXT        NOT NULL,

  -- `data`, not `payload`.
  --
  -- The build spec calls this column `payload`; this repository does not, and
  -- the divergence is deliberate rather than an oversight. Lab 1 gave an event
  -- its `:data` and lab 3 gave a message in transit its `:payload`, and
  -- REFERENCE.md explains why the two words must not collapse: a payload is an
  -- opaque blob somebody is carrying somewhere, and the contents of this table
  -- are the opposite of opaque -- they are the thing the whole system's
  -- answers are derived from. `bb audit` fails the build if the words drift.
  --
  -- `messaging.outbox` and `compliance.inbox` do call it `payload`, because
  -- they are transit and that is exactly what the word is reserved for.
  data           JSONB       NOT NULL,
  metadata       JSONB       NOT NULL DEFAULT '{}'::jsonb,
  occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

  -- Optimistic concurrency, and the reason lab 7 exists.
  --
  -- Without this, two threads that both read version 4 and both decide to
  -- append version 5 will both succeed, and the stream silently holds two
  -- different fifth events. The balance you fold out of it afterwards is
  -- wrong, and nothing anywhere reports an error. `concurrency_test.clj` runs
  -- exactly that race a hundred times.
  CONSTRAINT uq_accounts_aggregate_version UNIQUE (aggregate_id, version)
);

CREATE INDEX idx_accounts_stream_agg  ON accounts.event_stream (aggregate_id, version);
CREATE INDEX idx_accounts_stream_type ON accounts.event_stream (event_type, seq);

-- The index that makes §9's ad-hoc query argument true rather than aspirational.
--
-- `jsonb_path_ops` indexes only the paths-to-values, not the keys on their own,
-- so it is smaller and faster than the default for the containment queries
-- `/audit/query` actually issues. It cannot answer "which events have a
-- `currency` key at all", which is not a question anybody asks.
CREATE INDEX idx_accounts_stream_gin  ON accounts.event_stream USING GIN (data jsonb_path_ops);

RESET ROLE;
