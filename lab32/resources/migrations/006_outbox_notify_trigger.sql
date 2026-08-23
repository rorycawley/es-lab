-- Phase 2. The doorbell.
--
-- Everything before this migration is already correct. The reconciler delivers
-- every message, in order of being noticed, with a worst-case latency of one
-- polling interval. What it is not is *fast*: a deposit made a moment after a
-- reconciler pass waits the best part of ten seconds to reach Compliance.
--
-- This trigger is the entire fix, and it adds no delivery logic anywhere. It
-- rings a bell; `messaging/listener.clj` hears it and calls the same
-- `dispatcher/drain!` the reconciler calls. Deleting this file costs latency
-- and cannot cost an event, which is what acceptance test 9 asserts by
-- disabling the trigger and re-running everything.

SET ROLE messaging_module;

CREATE OR REPLACE FUNCTION messaging.notify_outbox() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  -- An empty payload, on purpose. This is a doorbell, not a delivery.
  --
  -- Gotcha #4 is why it has to stay empty. Postgres may fold identical
  -- (channel, payload) pairs emitted within one transaction into a single
  -- delivery -- which is exactly what we want from a doorbell, and exactly
  -- what would lose events if the payload carried an event id. A listener
  -- that learned *which* row to fetch would fetch one row and miss the other
  -- forty-nine.
  --
  -- Gotcha #5 says the same thing from the other side: the payload limit is
  -- under 8000 bytes, so embedding the event JSON here would work in testing
  -- and fail on the first large message. The listener is told only that
  -- something happened; it goes and looks.
  PERFORM pg_notify('outbox_events', '');
  RETURN NULL;
END;
$$;

-- FOR EACH STATEMENT, not FOR EACH ROW.
--
-- A thousand-row insert should ring the doorbell once. Row-level would emit a
-- thousand notifications, and although Postgres folds identical payloads
-- within a transaction, relying on that to undo an avoidable thousand-fold
-- amplification is not a design. The listener's response to one bell and to a
-- thousand is identical anyway: drain everything that is pending.
CREATE TRIGGER trg_outbox_notify
  AFTER INSERT ON messaging.outbox
  FOR EACH STATEMENT
  EXECUTE FUNCTION messaging.notify_outbox();

RESET ROLE;
