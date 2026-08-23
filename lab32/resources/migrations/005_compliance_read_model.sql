-- Compliance's whole reason to exist: single movements over the reporting
-- threshold, in a table somebody can query without knowing what an aggregate
-- is.
--
-- Lab 9's rule still holds. This is a read model, derived entirely from events
-- Compliance was sent, and it can be dropped and rebuilt without changing any
-- authoritative answer. `/audit/replay/compliance` does exactly that, and
-- `replay_test.clj` asserts the rebuilt table equals the original row for row.
--
-- `event_id` is the primary key rather than a generated one, and that is the
-- second half of the idempotency story. The inbox stops a duplicate *delivery*
-- from becoming a duplicate row; this stops a duplicate *projection* -- a
-- replay, a redriven dead letter, a handler that ran twice -- from doing the
-- same. Idempotency is cheapest when the natural key is already in the data.

SET ROLE compliance_module;

CREATE TABLE compliance.flagged_transactions (
  event_id   UUID PRIMARY KEY,
  account_id UUID          NOT NULL,
  -- NUMERIC, and it will be read back as a BigDecimal.
  --
  -- Gotcha #10. A float that holds 10000.10 holds 10000.099999999999 and a
  -- threshold test at 10,000 gets it right anyway, which is how this bug
  -- survives to production and then loses an argument with an auditor.
  amount     NUMERIC(19,4) NOT NULL,
  direction  TEXT          NOT NULL CHECK (direction IN ('credit', 'debit')),
  flagged_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_flagged_account ON compliance.flagged_transactions (account_id, flagged_at);

RESET ROLE;
