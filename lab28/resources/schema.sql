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

REVOKE CREATE ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA catalog AUTHORIZATION catalog_module;
CREATE SCHEMA ordering AUTHORIZATION ordering_module;
CREATE SCHEMA payments AUTHORIZATION payments_module;
CREATE SCHEMA notifications AUTHORIZATION notifications_module;

SET ROLE catalog_module;

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

-- `traceparent` is the one column here that is not business data.
--
-- It is written in the price-change transaction because that is the only
-- moment the producing trace exists. Mint it at relay time instead and the
-- consumer is joined to the relay's trace, which answers "what did the
-- background worker do" rather than "what happened to my request".
--
-- It is also the only column here with an expiry date. Traces are sampled and
-- retained for days, while `correlation_id` beside it is retained for as long
-- as the business keeps its records. They are not two names for one thing.
--
-- (Note the constraint this file's own loader imposes: `postgres.clj` splits
-- the script on semicolons, so a semicolon in a comment starts a statement.)
CREATE TABLE catalog.outbox (
  created_order  BIGSERIAL PRIMARY KEY,
  message_id     UUID      NOT NULL UNIQUE,
  message_type   TEXT      NOT NULL,
  fact_id        UUID      NOT NULL,
  causation_id   UUID      NOT NULL,
  correlation_id UUID      NOT NULL,
  traceparent    TEXT,
  product_id     UUID      NOT NULL,
  product_name   TEXT      NOT NULL,
  price_cents    INTEGER   NOT NULL CHECK (price_cents > 0),
  published      BOOLEAN   NOT NULL DEFAULT FALSE,
  attempts       INTEGER   NOT NULL DEFAULT 0,
  last_error     TEXT,
  -- Dead, not gone. Deleting the row would make the graveyard the only copy
  -- and put the burden of reconstructing an outbox row on whoever revigiving
  -- it. Flagging it keeps every column, so reviving is one UPDATE.
  dead           BOOLEAN   NOT NULL DEFAULT FALSE
);

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

CREATE INDEX catalog_outbox_pending_idx
  ON catalog.outbox (created_order) WHERE published = FALSE AND dead = FALSE;

-- Catalog has one too, and it should stay empty.
--
-- Its consumer is Ordering, which writes to its own tables and calls nobody.
-- Payments and Notifications talk to providers that can be down. Having the
-- same mechanism in all three is not uniformity for its own sake -- it means
-- "this queue has a dead letter in it" is one alert with one runbook, and the
-- one that fires here would be genuinely surprising.
CREATE TABLE catalog.dead_letter (
  message_id     UUID        PRIMARY KEY,
  message_type   TEXT        NOT NULL,
  fact_id        UUID        NOT NULL,
  correlation_id UUID        NOT NULL,
  message_body   TEXT        NOT NULL,
  attempts       INTEGER     NOT NULL,
  last_error     TEXT        NOT NULL,
  died_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

RESET ROLE;
SET ROLE ordering_module;

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
CREATE TABLE ordering.outbox (
  created_order  BIGSERIAL PRIMARY KEY,
  message_id     UUID      NOT NULL UNIQUE,
  message_type   TEXT      NOT NULL,
  fact_id        UUID      NOT NULL,
  causation_id   UUID      NOT NULL,
  correlation_id UUID      NOT NULL,
  traceparent    TEXT,
  order_id       UUID      NOT NULL,
  product_name   TEXT      NOT NULL,
  quantity       INTEGER   NOT NULL CHECK (quantity > 0),
  total_cents    INTEGER   NOT NULL CHECK (total_cents > 0),
  customer_email TEXT      NOT NULL,
  payment_method TEXT      NOT NULL,
  published      BOOLEAN   NOT NULL DEFAULT FALSE,
  attempts       INTEGER   NOT NULL DEFAULT 0,
  last_error     TEXT,
  -- Dead, not gone. Deleting the row would make the graveyard the only copy
  -- and put the burden of reconstructing an outbox row on whoever revigiving
  -- it. Flagging it keeps every column, so reviving is one UPDATE.
  dead           BOOLEAN   NOT NULL DEFAULT FALSE
);

CREATE INDEX ordering_outbox_pending_idx
  ON ordering.outbox (created_order) WHERE published = FALSE AND dead = FALSE;

-- The graveyard.
--
-- A relay retries a message because most delivery failures are brief. A
-- message that has failed a few times in a row is usually not one of those: it is
-- malformed, or names something that no longer exists, or trips a bug in the
-- consumer. Leaving it at the head of the queue blocks everything behind it,
-- and retrying it forever is a busy loop with a customer waiting at the end.
--
-- So it is moved here, with the error that killed it, and the queue moves on.
-- `message_body` is the whole message as JSON so that it can be replayed once
-- somebody has fixed the reason -- a dead letter queue you cannot drain is a
-- bin.
CREATE TABLE ordering.dead_letter (
  message_id     UUID        PRIMARY KEY,
  message_type   TEXT        NOT NULL,
  fact_id        UUID        NOT NULL,
  correlation_id UUID        NOT NULL,
  message_body   TEXT        NOT NULL,
  attempts       INTEGER     NOT NULL,
  last_error     TEXT        NOT NULL,
  died_at        TIMESTAMPTZ NOT NULL DEFAULT now()
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

CREATE TABLE payments.outbox (
  created_order  BIGSERIAL PRIMARY KEY,
  message_id     UUID      NOT NULL UNIQUE,
  message_type   TEXT      NOT NULL,
  fact_id        UUID      NOT NULL,
  causation_id   UUID      NOT NULL,
  correlation_id UUID      NOT NULL,
  traceparent    TEXT,
  payment_id     UUID      NOT NULL,
  order_id       UUID      NOT NULL,
  amount_cents   INTEGER   NOT NULL CHECK (amount_cents > 0),
  customer_email TEXT      NOT NULL,
  published      BOOLEAN   NOT NULL DEFAULT FALSE,
  attempts       INTEGER   NOT NULL DEFAULT 0,
  last_error     TEXT,
  dead           BOOLEAN   NOT NULL DEFAULT FALSE,
  -- A payment succeeds once, so it is announced once.
  --
  -- Two paths can reach that conclusion: the synchronous authorization, and a
  -- provider callback settling a payment that was still pending. Either may
  -- arrive first, both may race, and this constraint is what makes the
  -- announcement exactly-once without either path knowing about the other.
  UNIQUE (payment_id)
);

CREATE INDEX payments_outbox_pending_idx
  ON payments.outbox (created_order) WHERE published = FALSE AND dead = FALSE;

CREATE TABLE payments.dead_letter (
  message_id     UUID        PRIMARY KEY,
  message_type   TEXT        NOT NULL,
  fact_id        UUID        NOT NULL,
  correlation_id UUID        NOT NULL,
  message_body   TEXT        NOT NULL,
  attempts       INTEGER     NOT NULL,
  last_error     TEXT        NOT NULL,
  died_at        TIMESTAMPTZ NOT NULL DEFAULT now()
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
