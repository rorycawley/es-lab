CREATE TABLE service_requests (
    request_id   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    submitted_by TEXT        NOT NULL,
    title        TEXT        NOT NULL,
    description  TEXT        NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'submitted',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
