-- SQLite event store schema. Mirrors the port semantics in SPEC.md.

CREATE TABLE streams (
    stream_id       TEXT    NOT NULL PRIMARY KEY,
    stream_type     TEXT    NOT NULL,
    stream_position INTEGER NOT NULL,

    CONSTRAINT streams_stream_id_non_empty CHECK (stream_id <> ''),
    CONSTRAINT streams_stream_type_non_empty CHECK (stream_type <> ''),
    CONSTRAINT streams_stream_position_positive CHECK (stream_position > 0)
);

CREATE TABLE messages (
    global_position  INTEGER NOT NULL PRIMARY KEY,
    stream_id        TEXT    NOT NULL,
    stream_position  INTEGER NOT NULL,
    message_id       TEXT    NOT NULL,
    message_type     TEXT    NOT NULL,
    message_data     TEXT    NOT NULL,
    message_metadata TEXT    NOT NULL DEFAULT '{}',
    created          TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),

    CONSTRAINT messages_stream_fk FOREIGN KEY (stream_id) REFERENCES streams (stream_id),
    CONSTRAINT messages_stream_position_unique UNIQUE (stream_id, stream_position),
    CONSTRAINT messages_message_id_unique UNIQUE (message_id),
    CONSTRAINT messages_stream_position_positive CHECK (stream_position > 0),
    CONSTRAINT messages_message_id_non_empty CHECK (message_id <> ''),
    CONSTRAINT messages_message_type_non_empty CHECK (message_type <> ''),
    CONSTRAINT messages_message_data_object CHECK (
        json_valid(message_data) AND json_type(message_data) = 'object'
    ),
    CONSTRAINT messages_message_metadata_object CHECK (
        json_valid(message_metadata) AND json_type(message_metadata) = 'object'
    )
);

CREATE INDEX messages_stream_read_idx
    ON messages (stream_id, stream_position);
