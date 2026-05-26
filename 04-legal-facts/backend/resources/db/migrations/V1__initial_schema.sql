-- =============================================================================
-- V1: Initial schema
--
-- This lab uses disposable development databases, so the schema is kept as one
-- startup migration instead of a production-style migration history.
--
-- registration_events is the legal Register and process history. All other
-- tables are derived projections.
-- =============================================================================

CREATE TABLE IF NOT EXISTS registration_events
(
    id               UUID        NOT NULL,
    aggregate_id     UUID        NOT NULL,
    sequence_number  BIGINT      NOT NULL,
    event_type       TEXT        NOT NULL,
    event_data       JSONB       NOT NULL,
    occurred_at      TIMESTAMPTZ NOT NULL,
    inserted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    causation_id     UUID        NOT NULL,
    correlation_id   TEXT        NOT NULL,
    published        BOOLEAN     NOT NULL DEFAULT false,
    published_at     TIMESTAMPTZ,

    CONSTRAINT registration_events_pkey
        PRIMARY KEY (id),
    CONSTRAINT registration_events_aggregate_sequence_unique
        UNIQUE (aggregate_id, sequence_number)
);

CREATE INDEX registration_events_aggregate_id_seq_idx
    ON registration_events (aggregate_id, sequence_number ASC);
CREATE INDEX registration_events_causation_id_idx
    ON registration_events (causation_id);
CREATE INDEX registration_events_correlation_id_idx
    ON registration_events (correlation_id);
CREATE INDEX registration_events_event_type_idx
    ON registration_events (event_type);
CREATE INDEX registration_events_occurred_at_idx
    ON registration_events (occurred_at);
CREATE INDEX registration_events_unpublished_idx
    ON registration_events (occurred_at ASC)
    WHERE published = false;

-- Durable Register invariants. These are enforced where company-registered is
-- appended, not in eventually-consistent projections.
CREATE UNIQUE INDEX registration_events_registered_company_name_unique_idx
    ON registration_events ((event_data ->> 'company/name'))
    WHERE event_type = 'company-registered';

CREATE UNIQUE INDEX registration_events_registration_number_unique_idx
    ON registration_events ((event_data ->> 'registration/number'))
    WHERE event_type = 'company-registered';

-- =============================================================================
-- Examiner work queue projection
--
-- Populated from registration-application-submitted events.
-- =============================================================================

CREATE TABLE IF NOT EXISTS examiner_work_queue
(
    application_id  UUID        NOT NULL,
    applicant_id    TEXT        NOT NULL,
    company_name    TEXT        NOT NULL,
    submitted_at    TIMESTAMPTZ NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'waiting',

    CONSTRAINT examiner_work_queue_pkey PRIMARY KEY (application_id)
);

CREATE INDEX examiner_work_queue_status_submitted_at_idx
    ON examiner_work_queue (status, submitted_at ASC);

-- =============================================================================
-- Register projection
--
-- Eventually-consistent query projection rebuilt from company-registered
-- events. This is not the legal Register; the event store is.
-- =============================================================================

CREATE TABLE IF NOT EXISTS register_projection
(
    registration_number       TEXT        NOT NULL,
    company_name              TEXT        NOT NULL,
    application_id            TEXT        NOT NULL,
    registered_office_address TEXT        NOT NULL,
    registered_at             TIMESTAMPTZ NOT NULL,
    inserted_at               TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT register_projection_pkey PRIMARY KEY (registration_number),
    CONSTRAINT register_projection_company_name_unique UNIQUE (company_name)
);

CREATE INDEX register_projection_company_name_idx
    ON register_projection (company_name);
CREATE INDEX register_projection_registered_at_idx
    ON register_projection (registered_at DESC);

COMMENT ON TABLE register_projection IS
    'Eventually-consistent query projection rebuilt from company-registered events; not the legal source of truth.';
