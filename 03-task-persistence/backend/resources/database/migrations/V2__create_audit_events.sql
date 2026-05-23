CREATE TABLE audit_events (
    audit_event_id UUID        PRIMARY KEY,
    actor          TEXT        NOT NULL,
    action         TEXT        NOT NULL,
    subject_id     UUID        NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata       JSONB,
    CONSTRAINT audit_events_subject_id_fkey
        FOREIGN KEY (subject_id)
        REFERENCES service_requests (request_id)
);
