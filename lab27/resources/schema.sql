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

REVOKE CREATE ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA catalog AUTHORIZATION catalog_module;
CREATE SCHEMA ordering AUTHORIZATION ordering_module;

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
  published      BOOLEAN   NOT NULL DEFAULT FALSE
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
  ON catalog.outbox (created_order) WHERE published = FALSE;

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

CREATE TABLE ordering.inbox (
  fact_id          UUID        PRIMARY KEY,
  first_message_id UUID      NOT NULL,
  causation_id     UUID        NOT NULL,
  correlation_id   UUID        NOT NULL,
  handled_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

RESET ROLE;
