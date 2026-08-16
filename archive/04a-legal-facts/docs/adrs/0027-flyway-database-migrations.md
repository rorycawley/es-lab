# ADR-0027: Flyway for Database Migrations

*Status: Accepted.*
*Date: 2026-05-26.*


## Context

ADR-0003 decided on Postgres as the relational database. Schema migrations are
a separate concern: a tool is needed to create and evolve the schema in a
controlled, repeatable, idempotent way.

Two Clojure-ecosystem tools are commonly used for this:

- **Migratus** — a Clojure library that runs SQL migrations from the JVM process.
  Migrations are applied at application startup or via a CLI invocation.
- **Flyway** — a JVM library (also available as a standalone CLI and Docker image)
  that applies versioned SQL migrations. Widely used across JVM ecosystems.
  The reference implementation (`04-legal-facts`) uses Flyway.

The roadmap's architecture table listed Migratus as the default choice. In
practice, `04-legal-facts` (the reference implementation this project uses as
a structural reference) uses Flyway throughout: `deps.edn`, `docker-compose.yml`,
and the migration file naming convention (`V1__*.sql`).


## Decision

Use **Flyway** for database migrations, consistent with the reference
implementation.

Migrations run as a short-lived Docker Compose service (`migrate`) that exits
after applying all pending migrations. The backend service declares a
`depends_on` condition on `migrate: service_completed_successfully` so that the
backend never starts against an unmigrated schema.

Migration files live at `backend/resources/db/migration/` and follow Flyway's
versioned naming convention: `V1__create_event_store.sql`,
`V2__create_command_ledger.sql`, and so on.

`deps.edn` includes:

```clojure
org.flywaydb/flyway-core                {:mvn/version "12.6.2"}
org.flywaydb/flyway-database-postgresql {:mvn/version "12.6.2"}
```


## Consequences

- Migration files are plain SQL, readable and reviewable without Clojure
  tooling.
- The `migrate` service can be run independently of the backend for local
  schema setup (`bb db:up`), matching the workflow in `04-legal-facts`.
- Flyway's versioned migration model makes it straightforward to add migrations
  in CI and verify the schema is in the expected state before tests run.
- If the roadmap's standard migrator is later standardised as Migratus, this
  project would require a migration strategy. The Flyway SQL files themselves
  are portable; the wrapper code and `docker-compose.yml` service definition
  would change.
- Testcontainers-based adapter tests (Layer 2) apply migrations against a fresh
  container at the start of each test using the same Flyway configuration,
  ensuring the test schema always matches production.
