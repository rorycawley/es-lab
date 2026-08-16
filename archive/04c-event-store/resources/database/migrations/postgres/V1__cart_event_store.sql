CREATE TABLE streams (
    stream_id        UUID   NOT NULL PRIMARY KEY,
    stream_type      TEXT   NOT NULL,
    subject_id       UUID   NOT NULL UNIQUE,
    current_revision BIGINT NOT NULL,

    CONSTRAINT streams_revision_positive CHECK (current_revision > 0),
    CONSTRAINT streams_type_cart CHECK (stream_type = 'cart')
);

CREATE TABLE events (
    stream_id       UUID        NOT NULL,
    stream_revision BIGINT      NOT NULL,
    event_id         UUID        NOT NULL UNIQUE,
    event_type       TEXT        NOT NULL,
    event_version    INTEGER     NOT NULL,
    event_data       JSONB       NOT NULL,
    event_metadata   JSONB       NOT NULL,
    accepted_at      TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (stream_id, stream_revision),
    FOREIGN KEY (stream_id) REFERENCES streams (stream_id),
    CONSTRAINT events_revision_positive CHECK (stream_revision > 0),
    CONSTRAINT events_version_positive CHECK (event_version > 0),
    CONSTRAINT events_data_object CHECK (jsonb_typeof(event_data) = 'object'),
    CONSTRAINT events_metadata_object CHECK (jsonb_typeof(event_metadata) = 'object')
);

CREATE TABLE command_requests (
    request_id           UUID        NOT NULL PRIMARY KEY,
    command_type         TEXT        NOT NULL,
    canonical_input      JSONB       NOT NULL,
    canonical_input_hash TEXT        NOT NULL,
    cart_id              UUID        NOT NULL,
    original_result      JSONB       NOT NULL,
    accepted_at          TIMESTAMPTZ NOT NULL,

    CONSTRAINT command_input_object CHECK (jsonb_typeof(canonical_input) = 'object'),
    CONSTRAINT command_result_object CHECK (jsonb_typeof(original_result) = 'object')
);

CREATE TABLE cart_view_projection (
    cart_id  UUID   NOT NULL PRIMARY KEY,
    revision BIGINT NOT NULL,
    status   TEXT   NOT NULL,
    items    JSONB  NOT NULL,

    CONSTRAINT cart_view_revision_positive CHECK (revision > 0),
    CONSTRAINT cart_view_status CHECK (status IN ('open', 'closed')),
    CONSTRAINT cart_view_items_array CHECK (jsonb_typeof(items) = 'array')
);

CREATE TABLE cart_history_projection (
    cart_id       UUID        NOT NULL,
    revision      BIGINT      NOT NULL,
    change_type   TEXT        NOT NULL,
    accepted_at   TIMESTAMPTZ NOT NULL,
    business_data JSONB       NOT NULL,

    PRIMARY KEY (cart_id, revision),
    CONSTRAINT cart_history_revision_positive CHECK (revision > 0),
    CONSTRAINT cart_history_change_type CHECK (
        change_type IN (
            'product-item-added',
            'product-item-removed',
            'cart-confirmed',
            'cart-cancelled'
        )
    ),
    CONSTRAINT cart_history_data_object CHECK (jsonb_typeof(business_data) = 'object')
);

GRANT SELECT, INSERT, UPDATE ON streams TO cart_app;
GRANT SELECT, INSERT ON events TO cart_app;
GRANT SELECT, INSERT ON command_requests TO cart_app;
GRANT SELECT, INSERT, UPDATE ON cart_view_projection TO cart_app;
GRANT SELECT, INSERT ON cart_history_projection TO cart_app;
