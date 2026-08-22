-- One Postgres instance, but not one shared set of tables.
--
-- Each module has its own schema and login. The application connects with
-- those module identities, not the migration owner, so an accidental
-- cross-module query is rejected by Postgres rather than by convention.

-- Trigram matching, for the typos that are the real reason people say they
-- need a second datastore. It is a trusted extension in PG 13+, so no
-- superuser is required, but it must be installed before any SET ROLE and it
-- lands in `public`, where both module roles can reach its functions.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE ROLE catalog_module LOGIN PASSWORD 'catalog-pass';
CREATE ROLE ordering_module LOGIN PASSWORD 'ordering-pass';
CREATE ROLE payments_module LOGIN PASSWORD 'payments-pass';
CREATE ROLE notifications_module LOGIN PASSWORD 'notifications-pass';
CREATE ROLE websub_module LOGIN PASSWORD 'websub-pass';

REVOKE CREATE ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA catalog AUTHORIZATION catalog_module;
CREATE SCHEMA ordering AUTHORIZATION ordering_module;
CREATE SCHEMA payments AUTHORIZATION payments_module;
CREATE SCHEMA notifications AUTHORIZATION notifications_module;
CREATE SCHEMA websub AUTHORIZATION websub_module;

SET ROLE catalog_module;

-- One outbox shape for every module, and it stopped having typed columns.
--
-- Labs 25 to 28 gave each outbox a column per field of the one message that
-- module published. That worked because each module published exactly one
-- kind of thing. Ordering now *sends a command* as well as publishing a fact,
-- and the moment a table has to hold two message shapes the typed columns
-- were hiding what an outbox is: transport. So the envelope is stored whole,
-- as EDN, and the columns that remain are the ones the machinery routes on.
--
-- EDN and not JSON, for the reason lab 28 gave about dead letters:
-- `json/write-str` names a key with `name`, so `:command/type` would be
-- written as "type" and the namespace -- which is the routing key -- would be
-- silently gone.
CREATE TABLE catalog.outbox (
  created_order  BIGSERIAL   PRIMARY KEY,
  message_id     UUID        NOT NULL UNIQUE,
  message_kind   TEXT        NOT NULL CHECK (message_kind IN ('command', 'integration-event')),
  message_type   TEXT        NOT NULL,
  message_body   TEXT        NOT NULL,
  correlation_id UUID        NOT NULL,
  traceparent    TEXT,
  published      BOOLEAN     NOT NULL DEFAULT FALSE,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX catalog_outbox_pending_idx
  ON catalog.outbox (created_order) WHERE published = FALSE;

-- One delivery record per consumer, which is the whole point.
--
-- Labs 25 to 28 marked the *message* delivered, so a message with two
-- consumers had one shared fate: if either refused it, the other was sent it
-- again on every retry, and the message could be dead-lettered while most
-- consumers had accepted it. Fan-out with a shared failure domain is not
-- fan-out.
--
-- Rows are expanded by the relay from the routing table on first sight, not
-- written with the outbox row, so a consumer deployed after a message was
-- queued still receives it.
CREATE TABLE catalog.delivery (
  message_id UUID    NOT NULL REFERENCES catalog.outbox (message_id),
  consumer   TEXT    NOT NULL,
  delivered  BOOLEAN NOT NULL DEFAULT FALSE,
  attempts   INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  dead       BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (message_id, consumer)
);

CREATE INDEX catalog_delivery_pending_idx
  ON catalog.delivery (message_id) WHERE delivered = FALSE AND dead = FALSE;

-- The graveyard, now per consumer rather than per message.
CREATE TABLE catalog.dead_letter (
  message_id     UUID        NOT NULL,
  consumer       TEXT        NOT NULL,
  message_kind   TEXT        NOT NULL,
  message_type   TEXT        NOT NULL,
  message_body   TEXT        NOT NULL,
  correlation_id UUID        NOT NULL,
  attempts       INTEGER     NOT NULL,
  last_error     TEXT        NOT NULL,
  died_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (message_id, consumer)
);

-- The search index is a projection, and this is what that means in DDL.
--
-- `description` is the retained source text. `search_document` is derived from
-- it and never written by anything: no trigger, no application code, no
-- maintenance job. Delete the index and rebuild it and the answers do not
-- change, which is lab 9's rule for a read model.
--
-- The two-argument `to_tsvector('english', ...)` is not a style choice.
-- Postgres refuses the one-argument form here, because it depends on the
-- `default_text_search_config` setting and an index whose contents vary with a
-- session GUC could not be dumped and restored. Naming the configuration makes
-- the expression immutable -- and makes 'english' part of this index's
-- identity, the way a fold version is part of a snapshot's in lab 17.
--
-- The name is weighted above the description, because a product *called*
-- pistachio is a better hit than one that merely mentions pistachio.
CREATE TABLE catalog.product (
  product_id          UUID        PRIMARY KEY,
  product_name        TEXT        NOT NULL,
  description         TEXT        NOT NULL DEFAULT '',
  current_price_cents INTEGER     NOT NULL CHECK (current_price_cents > 0),
  -- What the truck pays for it. Legitimately stored, never disclosed.
  --
  -- Labs 15, 24, 26 and 27 each kept a field out of one more place: an
  -- append-only history, a response, telemetry, a search index. Lab 29 adds
  -- the boundary where the reader is a stranger on the internet, and a public
  -- WebSub topic is the easiest of the five to leak through, because nobody
  -- reviewing a projection thinks of it as an API.
  supplier_cost_cents INTEGER     NOT NULL DEFAULT 0,
  search_document     tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(product_name, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(description,  '')), 'B')
  ) STORED
);

CREATE INDEX product_search_idx ON catalog.product USING GIN (search_document);

-- A second index over the raw name, for when the query is misspelled and the
-- lexemes therefore do not match at all.
CREATE INDEX product_name_trgm_idx
  ON catalog.product USING GIN (product_name gin_trgm_ops);


CREATE TABLE catalog.command_ledger (
  command_id     UUID        PRIMARY KEY,
  correlation_id UUID        NOT NULL,
  product_id     UUID        NOT NULL,
  product_name   TEXT        NOT NULL,
  price_cents    INTEGER     NOT NULL CHECK (price_cents > 0),
  fact_id        UUID        NOT NULL UNIQUE,
  message_id     UUID        NOT NULL,
  handled_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- `describe-product` gets its own ledger rather than nullable columns in
-- `command_ledger`. The two commands identify different intents and record
-- different things, and lab 25's rule is that a slice owns its SQL.
CREATE TABLE catalog.description_ledger (
  command_id     UUID        PRIMARY KEY,
  correlation_id UUID        NOT NULL,
  product_id     UUID        NOT NULL,
  description    TEXT        NOT NULL,
  handled_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);



RESET ROLE;
SET ROLE ordering_module;

-- One outbox shape for every module, and it stopped having typed columns.
--
-- Labs 25 to 28 gave each outbox a column per field of the one message that
-- module published. That worked because each module published exactly one
-- kind of thing. Ordering now *sends a command* as well as publishing a fact,
-- and the moment a table has to hold two message shapes the typed columns
-- were hiding what an outbox is: transport. So the envelope is stored whole,
-- as EDN, and the columns that remain are the ones the machinery routes on.
--
-- EDN and not JSON, for the reason lab 28 gave about dead letters:
-- `json/write-str` names a key with `name`, so `:command/type` would be
-- written as "type" and the namespace -- which is the routing key -- would be
-- silently gone.
CREATE TABLE ordering.outbox (
  created_order  BIGSERIAL   PRIMARY KEY,
  message_id     UUID        NOT NULL UNIQUE,
  message_kind   TEXT        NOT NULL CHECK (message_kind IN ('command', 'integration-event')),
  message_type   TEXT        NOT NULL,
  message_body   TEXT        NOT NULL,
  correlation_id UUID        NOT NULL,
  traceparent    TEXT,
  published      BOOLEAN     NOT NULL DEFAULT FALSE,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ordering_outbox_pending_idx
  ON ordering.outbox (created_order) WHERE published = FALSE;

-- One delivery record per consumer, which is the whole point.
--
-- Labs 25 to 28 marked the *message* delivered, so a message with two
-- consumers had one shared fate: if either refused it, the other was sent it
-- again on every retry, and the message could be dead-lettered while most
-- consumers had accepted it. Fan-out with a shared failure domain is not
-- fan-out.
--
-- Rows are expanded by the relay from the routing table on first sight, not
-- written with the outbox row, so a consumer deployed after a message was
-- queued still receives it.
CREATE TABLE ordering.delivery (
  message_id UUID    NOT NULL REFERENCES ordering.outbox (message_id),
  consumer   TEXT    NOT NULL,
  delivered  BOOLEAN NOT NULL DEFAULT FALSE,
  attempts   INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  dead       BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (message_id, consumer)
);

CREATE INDEX ordering_delivery_pending_idx
  ON ordering.delivery (message_id) WHERE delivered = FALSE AND dead = FALSE;

-- The graveyard, now per consumer rather than per message.
CREATE TABLE ordering.dead_letter (
  message_id     UUID        NOT NULL,
  consumer       TEXT        NOT NULL,
  message_kind   TEXT        NOT NULL,
  message_type   TEXT        NOT NULL,
  message_body   TEXT        NOT NULL,
  correlation_id UUID        NOT NULL,
  attempts       INTEGER     NOT NULL,
  last_error     TEXT        NOT NULL,
  died_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (message_id, consumer)
);

CREATE TABLE ordering.price_book (
  product_id          UUID        PRIMARY KEY,
  product_name        TEXT        NOT NULL,
  current_price_cents INTEGER     NOT NULL CHECK (current_price_cents > 0)
);

-- `customer_email` is new in lab 26, and it is here to be legitimate.
--
-- Ordering needs somewhere to send the receipt, so the column is not a
-- mistake. What would be a mistake is the same string appearing in a span
-- attribute or a log body, where lab 15's erasure cannot reach it and a
-- third party's retention policy decides how long it lives.
-- `redaction_test.clj` asserts it never gets out.
CREATE TABLE ordering.orders (
  order_id         UUID      PRIMARY KEY,
  correlation_id   UUID      NOT NULL,
  product_id       UUID      NOT NULL,
  product_name     TEXT      NOT NULL,
  quantity         INTEGER   NOT NULL CHECK (quantity > 0),
  unit_price_cents INTEGER   NOT NULL CHECK (unit_price_cents > 0),
  total_cents      INTEGER   NOT NULL CHECK (total_cents > 0),
  customer_email   TEXT      NOT NULL,
  -- An opaque token the customer's client obtained at checkout. The domain
  -- never parses it, which is why a provider-shaped value can live in a column
  -- without the provider living in the code.
  payment_method   TEXT      NOT NULL,
  -- Note what is not in here.
  --
  -- `customer_email` is the obvious thing to make searchable and it is the one
  -- field that must not be. Lab 15 sealed personal data so that erasing a key
  -- erases a subject, lab 24 shaped it out of responses and lab 26 kept it out
  -- of telemetry. A trigram index over this column would undo all three by
  -- making partial-email fishing cheap and fast, for anyone who can reach a
  -- search box. An operator who needs one order gets an exact-match lookup.
  search_document  tsvector GENERATED ALWAYS AS (
    to_tsvector('english', coalesce(product_name, ''))
  ) STORED
);

CREATE INDEX orders_search_idx ON ordering.orders USING GIN (search_document);

-- Ordering acquires an outbox in lab 28, because it now has something other
-- modules need to know: an order was placed and somebody should be charged.




-- A process manager's durable state, and the reason it is a process manager.
--
-- A policy decides from the triggering fact alone and needs to remember
-- nothing. This one cannot: "has this order been paid for?" is not answerable
-- from the message that just arrived, so the answer lives here, one row per
-- order, advanced by each step of the conversation.
--
-- Note what is absent: attempts, delivery state, retry counts. Those belong
-- to the transport. A process manager that knows how many times a message was
-- redelivered has started coordinating infrastructure instead of business.
CREATE TABLE ordering.fulfilment (
  order_id       UUID        PRIMARY KEY,
  correlation_id UUID        NOT NULL,
  state          TEXT        NOT NULL
                 CHECK (state IN ('awaiting-payment', 'paid', 'payment-failed')),
  total_cents    INTEGER     NOT NULL CHECK (total_cents > 0),
  customer_email TEXT        NOT NULL,
  payment_method TEXT        NOT NULL,
  started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  settled_at     TIMESTAMPTZ
);

CREATE TABLE ordering.inbox (
  fact_id          UUID        PRIMARY KEY,
  first_message_id UUID      NOT NULL,
  causation_id     UUID        NOT NULL,
  correlation_id   UUID        NOT NULL,
  handled_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

RESET ROLE;

RESET ROLE;
SET ROLE payments_module;

-- One outbox shape for every module, and it stopped having typed columns.
--
-- Labs 25 to 28 gave each outbox a column per field of the one message that
-- module published. That worked because each module published exactly one
-- kind of thing. Ordering now *sends a command* as well as publishing a fact,
-- and the moment a table has to hold two message shapes the typed columns
-- were hiding what an outbox is: transport. So the envelope is stored whole,
-- as EDN, and the columns that remain are the ones the machinery routes on.
--
-- EDN and not JSON, for the reason lab 28 gave about dead letters:
-- `json/write-str` names a key with `name`, so `:command/type` would be
-- written as "type" and the namespace -- which is the routing key -- would be
-- silently gone.
CREATE TABLE payments.outbox (
  created_order  BIGSERIAL   PRIMARY KEY,
  message_id     UUID        NOT NULL UNIQUE,
  message_kind   TEXT        NOT NULL CHECK (message_kind IN ('command', 'integration-event')),
  message_type   TEXT        NOT NULL,
  message_body   TEXT        NOT NULL,
  correlation_id UUID        NOT NULL,
  traceparent    TEXT,
  published      BOOLEAN     NOT NULL DEFAULT FALSE,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX payments_outbox_pending_idx
  ON payments.outbox (created_order) WHERE published = FALSE;

-- One delivery record per consumer, which is the whole point.
--
-- Labs 25 to 28 marked the *message* delivered, so a message with two
-- consumers had one shared fate: if either refused it, the other was sent it
-- again on every retry, and the message could be dead-lettered while most
-- consumers had accepted it. Fan-out with a shared failure domain is not
-- fan-out.
--
-- Rows are expanded by the relay from the routing table on first sight, not
-- written with the outbox row, so a consumer deployed after a message was
-- queued still receives it.
CREATE TABLE payments.delivery (
  message_id UUID    NOT NULL REFERENCES payments.outbox (message_id),
  consumer   TEXT    NOT NULL,
  delivered  BOOLEAN NOT NULL DEFAULT FALSE,
  attempts   INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  dead       BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (message_id, consumer)
);

CREATE INDEX payments_delivery_pending_idx
  ON payments.delivery (message_id) WHERE delivered = FALSE AND dead = FALSE;

-- The graveyard, now per consumer rather than per message.
CREATE TABLE payments.dead_letter (
  message_id     UUID        NOT NULL,
  consumer       TEXT        NOT NULL,
  message_kind   TEXT        NOT NULL,
  message_type   TEXT        NOT NULL,
  message_body   TEXT        NOT NULL,
  correlation_id UUID        NOT NULL,
  attempts       INTEGER     NOT NULL,
  last_error     TEXT        NOT NULL,
  died_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (message_id, consumer)
);

-- Integration events consumed from other modules, claimed by the stable fact
-- id exactly as lab 25 established.
CREATE TABLE payments.inbox (
  fact_id          UUID        PRIMARY KEY,
  first_message_id UUID        NOT NULL,
  correlation_id   UUID        NOT NULL,
  handled_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One payment per order, enforced by the database rather than by hoping the
-- message is not redelivered.
--
-- `payment_id` is ours and is also the key we hand the gateway as its
-- idempotency key. That is the whole trick: an identifier we control, chosen
-- before the first call, is what makes a retry recognisable to somebody
-- else's system.
--
-- `gateway_reference` is the only vendor-shaped value in this schema, and it
-- is opaque here on purpose. Nothing branches on it, nothing parses it.
CREATE TABLE payments.payment (
  payment_id        UUID        PRIMARY KEY,
  order_id          UUID        NOT NULL UNIQUE,
  -- The fact that caused this payment to be attempted.
  --
  -- It is here so that both paths to success can name the same cause. A
  -- callback knows the provider's event id and nothing about why we ever asked
  -- -- and "the provider told us" is not the answer to "why did this customer
  -- get charged". Causation stays one of our identifiers, and the provider's
  -- own is kept in `webhook_inbox` where it belongs.
  order_fact_id     UUID        NOT NULL,
  amount_cents      INTEGER     NOT NULL CHECK (amount_cents > 0),
  currency          TEXT        NOT NULL,
  -- requested   written down, not yet asked or the answer never arrived
  -- authorized   the provider said yes, synchronously
  -- pending      the provider has it and is not finished (3-D Secure, review)
  -- settled      the provider confirmed it, by callback
  -- declined     the provider said no
  status            TEXT        NOT NULL
                    CHECK (status IN ('requested', 'authorized', 'pending',
                                      'settled', 'declined')),
  gateway_reference TEXT,
  decline_reason    TEXT,
  correlation_id    UUID        NOT NULL,
  customer_email    TEXT        NOT NULL,
  payment_method    TEXT        NOT NULL,
  requested_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  settled_at        TIMESTAMPTZ
);

-- Callbacks from the provider are a second, independent inbox.
--
-- It is keyed by the provider's event id and not by ours, because we did not
-- mint it and have nothing else to recognise a redelivery by. Providers retry
-- on any non-2xx, so a duplicate is the normal case rather than the exception.
CREATE TABLE payments.webhook_inbox (
  provider          TEXT        NOT NULL,
  provider_event_id TEXT        NOT NULL,
  event_type        TEXT        NOT NULL,
  received_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (provider, provider_event_id)
);




RESET ROLE;
SET ROLE notifications_module;

CREATE TABLE notifications.inbox (
  fact_id          UUID        PRIMARY KEY,
  first_message_id UUID        NOT NULL,
  correlation_id   UUID        NOT NULL,
  handled_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The ledger that makes a duplicate email *visible*, which is as far as this
-- integration can go. An email provider has no idempotency key to offer, so
-- `attempts` is not a metric here -- it is the honest record of a promise this
-- module cannot make.
CREATE TABLE notifications.notification (
  notification_id    UUID        PRIMARY KEY,
  fact_id            UUID        NOT NULL UNIQUE,
  recipient          TEXT        NOT NULL,
  subject            TEXT        NOT NULL,
  body               TEXT        NOT NULL,
  status             TEXT        NOT NULL
                     CHECK (status IN ('queued', 'sent', 'failed')),
  provider_reference TEXT,
  attempts           INTEGER     NOT NULL DEFAULT 0,
  failure_reason     TEXT,
  correlation_id     UUID        NOT NULL,
  queued_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  sent_at            TIMESTAMPTZ
);

RESET ROLE;

RESET ROLE;
SET ROLE websub_module;

-- The public face of a product, and nothing else.
--
-- This is a projection, not a table Catalog owns: WebSub consumes Catalog's
-- integration event and keeps its own copy of the disclosable fields. That
-- duplication is the point. A topic served by joining Catalog's table would
-- publish whatever a future migration adds to it.
CREATE TABLE websub.public_product (
  product_id   UUID        PRIMARY KEY,
  product_name TEXT        NOT NULL,
  description  TEXT        NOT NULL DEFAULT '',
  price_cents  INTEGER     NOT NULL CHECK (price_cents > 0),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  version      BIGINT      NOT NULL DEFAULT 1
);

CREATE TABLE websub.inbox (
  fact_id          UUID        PRIMARY KEY,
  first_message_id UUID        NOT NULL,
  correlation_id   UUID        NOT NULL,
  handled_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A subscription only exists once the subscriber proved it asked for one.
--
-- `verified_at` is null between the request and the callback echoing our
-- challenge, and an unverified subscription is never delivered to. That
-- handshake is the whole anti-abuse mechanism of WebSub: without it, anyone
-- could subscribe somebody else's server to a firehose.
--
-- `secret` is per subscriber and never leaves except as an HMAC.
CREATE TABLE websub.subscription (
  subscription_id UUID        PRIMARY KEY,
  topic           TEXT        NOT NULL,
  callback        TEXT        NOT NULL,
  secret          TEXT,
  lease_seconds   INTEGER     NOT NULL,
  requested_at    TIMESTAMPTZ NOT NULL,
  verified_at     TIMESTAMPTZ,
  expires_at      TIMESTAMPTZ,
  attempts        INTEGER     NOT NULL DEFAULT 0,
  last_error      TEXT,
  UNIQUE (topic, callback)
);

CREATE INDEX websub_subscription_live_idx
  ON websub.subscription (topic) WHERE verified_at IS NOT NULL;

RESET ROLE;
