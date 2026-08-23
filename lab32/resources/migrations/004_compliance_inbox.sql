-- The idempotency guarantee, and it is one line of DDL.
--
-- Every other mechanism in this lab exists to deliver *at least* once: the
-- reconciler resends, the dispatcher retries, a crash mid-transaction means
-- the whole thing happens again. None of that is safe unless the receiving end
-- can absorb a repeat, and this is where it does. Redelivery is not an error
-- to handle, it is a no-op -- `ON CONFLICT (event_id) DO NOTHING` and the
-- second copy is gone.
--
-- Lab 20 made the same point with `handle-once!`, keyed on the *fact's* id
-- rather than the delivery's. Same rule here: `event_id` is carried unchanged
-- from `accounts.event_stream`, so a message republished in a fresh envelope
-- still deduplicates.

SET ROLE compliance_module;

CREATE TABLE compliance.inbox (
  seq             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id        UUID        NOT NULL,
  event_type      TEXT        NOT NULL,
  partition_key   TEXT        NOT NULL,
  payload         JSONB       NOT NULL,
  status          messaging.msg_status NOT NULL DEFAULT 'PENDING',
  attempts        INT         NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_error      TEXT,
  received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

  CONSTRAINT uq_compliance_inbox_event UNIQUE (event_id)
);

CREATE INDEX idx_compliance_inbox_pending
  ON compliance.inbox (partition_key, seq)
  WHERE status = 'PENDING';

ALTER TABLE compliance.inbox SET (autovacuum_vacuum_scale_factor = 0.01);

RESET ROLE;

-- The dispatcher's one cross-module privilege, and the reason `messaging` has
-- a login of its own. It may put an event into Compliance's inbox. It may not
-- work one, or touch the read model behind it -- so the transport cannot
-- quietly become the consumer.
GRANT USAGE  ON SCHEMA compliance TO messaging_module;
GRANT INSERT ON compliance.inbox  TO messaging_module;

-- One column, and only one.
--
-- `INSERT ... ON CONFLICT (event_id) DO NOTHING` needs SELECT on the columns
-- of the arbiter index -- naming a conflict target is a read, even when the
-- action is to do nothing. Without this the dispatcher fails with "permission
-- denied for table inbox", which is a confusing way for Postgres to say
-- "you asked me to look at event_id".
--
-- The lazy fix is a bare `ON CONFLICT DO NOTHING`, which needs no SELECT
-- because it names no target. It also swallows a conflict on *any* constraint,
-- so the day somebody adds a second unique index here, a genuine collision
-- starts being discarded as a duplicate delivery. The other lazy fix is
-- `GRANT SELECT` on the table, which hands the transport every message body it
-- ever carried.
--
-- A column-level grant is neither: the dispatcher may check the id it is
-- inserting and cannot read a payload, a status or an error.
GRANT SELECT (event_id) ON compliance.inbox TO messaging_module;
