-- One Postgres instance, but not one shared set of tables.
--
-- Each module has its own schema and login. The application connects with
-- those module identities, not the migration owner, so an accidental
-- cross-module query is rejected by Postgres rather than by convention.

CREATE ROLE catalog_module LOGIN PASSWORD 'catalog-pass';
CREATE ROLE ordering_module LOGIN PASSWORD 'ordering-pass';

REVOKE CREATE ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA catalog AUTHORIZATION catalog_module;
CREATE SCHEMA ordering AUTHORIZATION ordering_module;

SET ROLE catalog_module;

CREATE TABLE catalog.product (
  product_id          UUID        PRIMARY KEY,
  product_name        TEXT        NOT NULL,
  current_price_cents INTEGER     NOT NULL CHECK (current_price_cents > 0)
);

CREATE TABLE catalog.outbox (
  created_order  BIGSERIAL PRIMARY KEY,
  message_id     UUID      NOT NULL UNIQUE,
  message_type   TEXT      NOT NULL,
  fact_id        UUID      NOT NULL,
  causation_id   UUID      NOT NULL,
  correlation_id UUID      NOT NULL,
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

CREATE INDEX catalog_outbox_pending_idx
  ON catalog.outbox (created_order) WHERE published = FALSE;

RESET ROLE;
SET ROLE ordering_module;

CREATE TABLE ordering.price_book (
  product_id          UUID        PRIMARY KEY,
  product_name        TEXT        NOT NULL,
  current_price_cents INTEGER     NOT NULL CHECK (current_price_cents > 0)
);

CREATE TABLE ordering.orders (
  order_id         UUID      PRIMARY KEY,
  correlation_id   UUID      NOT NULL,
  product_id       UUID      NOT NULL,
  product_name     TEXT      NOT NULL,
  quantity         INTEGER   NOT NULL CHECK (quantity > 0),
  unit_price_cents INTEGER   NOT NULL CHECK (unit_price_cents > 0),
  total_cents      INTEGER   NOT NULL CHECK (total_cents > 0)
);

CREATE TABLE ordering.inbox (
  fact_id          UUID        PRIMARY KEY,
  first_message_id UUID      NOT NULL,
  causation_id     UUID        NOT NULL,
  correlation_id   UUID        NOT NULL,
  handled_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

RESET ROLE;
