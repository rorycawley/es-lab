CREATE TABLE streams (
    stream_id        TEXT    NOT NULL PRIMARY KEY,
    stream_type      TEXT    NOT NULL,
    subject_id       TEXT    NOT NULL UNIQUE,
    current_revision INTEGER NOT NULL,

    CONSTRAINT streams_revision_positive CHECK (current_revision > 0),
    CONSTRAINT streams_type_cart CHECK (stream_type = 'cart')
);

CREATE TABLE events (
    stream_id       TEXT    NOT NULL,
    stream_revision INTEGER NOT NULL,
    event_id         TEXT    NOT NULL UNIQUE,
    event_type       TEXT    NOT NULL,
    event_version    INTEGER NOT NULL,
    event_data       TEXT    NOT NULL,
    event_metadata   TEXT    NOT NULL,
    accepted_at      TEXT    NOT NULL,

    PRIMARY KEY (stream_id, stream_revision),
    FOREIGN KEY (stream_id) REFERENCES streams (stream_id),
    CONSTRAINT events_revision_positive CHECK (stream_revision > 0),
    CONSTRAINT events_version_positive CHECK (event_version > 0),
    CONSTRAINT events_data_object CHECK (
        json_valid(event_data) AND json_type(event_data) = 'object'
    ),
    CONSTRAINT events_metadata_object CHECK (
        json_valid(event_metadata) AND json_type(event_metadata) = 'object'
    )
);

CREATE TABLE command_requests (
    request_id           TEXT NOT NULL PRIMARY KEY,
    command_type         TEXT NOT NULL,
    canonical_input      TEXT NOT NULL,
    canonical_input_hash TEXT NOT NULL,
    cart_id              TEXT NOT NULL,
    original_result      TEXT NOT NULL,
    accepted_at          TEXT NOT NULL,

    CONSTRAINT command_input_object CHECK (
        json_valid(canonical_input) AND json_type(canonical_input) = 'object'
    ),
    CONSTRAINT command_result_object CHECK (
        json_valid(original_result) AND json_type(original_result) = 'object'
    )
);

CREATE TABLE cart_view_projection (
    cart_id  TEXT    NOT NULL PRIMARY KEY,
    revision INTEGER NOT NULL,
    status   TEXT    NOT NULL,
    items    TEXT    NOT NULL,

    CONSTRAINT cart_view_revision_positive CHECK (revision > 0),
    CONSTRAINT cart_view_status CHECK (status IN ('open', 'closed')),
    CONSTRAINT cart_view_items_array CHECK (
        json_valid(items) AND json_type(items) = 'array'
    )
);

CREATE TABLE cart_history_projection (
    cart_id       TEXT    NOT NULL,
    revision      INTEGER NOT NULL,
    change_type   TEXT    NOT NULL,
    accepted_at   TEXT    NOT NULL,
    business_data TEXT    NOT NULL,

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
    CONSTRAINT cart_history_data_object CHECK (
        json_valid(business_data) AND json_type(business_data) = 'object'
    )
);
