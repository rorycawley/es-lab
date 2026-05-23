CREATE TABLE audit_events (
    audit_event_id UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    actor          TEXT        NOT NULL,
    action         TEXT        NOT NULL,
    subject_id     UUID        NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata       JSONB
);
