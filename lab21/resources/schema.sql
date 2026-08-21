-- The compare-and-set token. Event uniqueness alone cannot reject a caller
-- whose expected version is ahead of the real stream.
CREATE TABLE IF NOT EXISTS stream_head (
  stream_id       UUID   PRIMARY KEY,
  stream_version  BIGINT NOT NULL,
  CONSTRAINT stream_head_version_non_negative CHECK (stream_version >= 0)
);

CREATE TABLE IF NOT EXISTS event (
  global_position BIGSERIAL   PRIMARY KEY,
  xid             xid8        NOT NULL DEFAULT pg_current_xact_id(),
  event_id        UUID        NOT NULL,
  event_type      TEXT        NOT NULL,
  stream_id       UUID        NOT NULL,
  stream_version  BIGINT      NOT NULL,
  occurred_at     TIMESTAMPTZ NOT NULL,
  recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  data            JSONB       NOT NULL,
  metadata        JSONB       NOT NULL DEFAULT '{}'::jsonb,

  CONSTRAINT event_id_unique UNIQUE (event_id),
  CONSTRAINT stream_version_unique UNIQUE (stream_id, stream_version),
  CONSTRAINT stream_version_positive CHECK (stream_version > 0),
  CONSTRAINT data_is_object CHECK (jsonb_typeof(data) = 'object'),
  CONSTRAINT metadata_is_object CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX IF NOT EXISTS event_stream_idx ON event (stream_id, stream_version);

-- Written in the same transaction as the facts and command ledger.
CREATE TABLE IF NOT EXISTS outbox (
  id              BIGSERIAL   PRIMARY KEY,
  message_id      UUID        NOT NULL,
  message_type    TEXT        NOT NULL,
  recipient       TEXT        NOT NULL,
  causation_id    UUID        NOT NULL,
  correlation_id  UUID        NOT NULL,
  payload         JSONB       NOT NULL,
  enqueued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  sent_at         TIMESTAMPTZ,

  CONSTRAINT outbox_message_id_unique UNIQUE (message_id),
  CONSTRAINT outbox_payload_is_object CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX IF NOT EXISTS outbox_pending_idx ON outbox (id) WHERE sent_at IS NULL;

-- Command identity is independent of whether the decision emitted any facts.
CREATE TABLE IF NOT EXISTS command_ledger (
  command_id      UUID        NOT NULL PRIMARY KEY,
  stream_id       UUID        NOT NULL,
  command_type    TEXT        NOT NULL,
  correlation_id  UUID        NOT NULL,
  command_data    JSONB       NOT NULL,
  event_count     INTEGER     NOT NULL,
  handled_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

  CONSTRAINT event_count_not_negative CHECK (event_count >= 0),
  CONSTRAINT command_data_is_object CHECK (jsonb_typeof(command_data) = 'object')
);
