# Architecture

*Status: Accepted.*
*Date: 2026-05-26.*

This document describes the system structure at four levels of abstraction:
system context, containers, data, and security. All structural decisions
referenced here are recorded in [`adrs/`](adrs/README.md). Diagrams are
maintained as Mermaid source so they render in GitHub and most editors without
external tooling.

---

## C4 Level 1 — System Context

*Who uses the system, and what external systems does it depend on?*

```mermaid
C4Context
    title System Context — Companies Registry

    Person(applicant, "Applicant", "Submits company registration applications and pays fees")
    Person(examiner, "Examiner", "Reviews applications and prepares them for decision")
    Person(registrar, "Registrar", "Makes final registration decisions")
    Person_Ext(public, "Public", "Searches and inspects the public register")

    System(registry, "Companies Registry", "Processes registration applications. Maintains the authoritative, permanent, tamper-evident Register of companies. Enforces compliance obligations.")

    System_Ext(identity_broker, "Identity Broker", "OIDC provider. Issues verified identity tokens for natural persons acting in the system.")
    System_Ext(payment_gateway, "Payment Gateway", "Processes registration fee payments.")
    System_Ext(address_validation, "Address Validation", "Validates that a proposed registered office address is a recognised postal address.")
    System_Ext(email_service, "Email Service", "Delivers registration confirmations and compliance notices to applicants and companies.")

    Rel(applicant, registry, "Submits drafts, uploads documents, pays fee, receives registration confirmation")
    Rel(examiner, registry, "Reviews applications, requests amendments, marks ready for decision")
    Rel(registrar, registry, "Approves or rejects applications, strikes off companies")
    Rel(public, registry, "Searches company register, views company details and history")

    Rel(registry, identity_broker, "Authenticates users, retrieves verified person claims", "OIDC / JWT")
    Rel(registry, payment_gateway, "Initiates fee payment, receives payment outcome", "HTTPS API")
    Rel(registry, address_validation, "Validates registered office address at submission", "HTTPS API")
    Rel(registry, email_service, "Sends notifications after registration and compliance events", "HTTPS API")
```

### Key Relationships

| Relationship | Nature |
|---|---|
| Registry ← Identity Broker | The Registry consumes verified identity outcomes; it does not perform identity proofing. (ADR-0007) |
| Registry ← Payment Gateway | Payment is part of the submission boundary; the gateway integration is outside the Registry contract. (ADR-0008) |
| Registry → Address Validation | Address is validated at draft submission, not at approval. The Registry records the validation outcome as a fact. |
| Registry → Email Service | Notifications are published via the transactional outbox after domain events. The Registry does not call the email service directly from command handlers. (ADR-0005) |

---

## C4 Level 2 — Containers

*What are the deployable units, and how do they communicate?*

The Registry is a single deployable modular monolith (ADR-0012). There is no
separate message broker — the event bus is in-process (ADR-0015). External
publication uses the transactional outbox pattern (ADR-0005), relayed by a
background thread (ADR-0013).

```mermaid
C4Container
    title Containers — Companies Registry

    Person(applicant, "Applicant")
    Person(examiner, "Examiner")
    Person(registrar, "Registrar")
    Person_Ext(public, "Public")

    System_Ext(identity_broker, "Identity Broker", "OIDC")
    System_Ext(payment_gateway, "Payment Gateway")
    System_Ext(address_validation, "Address Validation")
    System_Ext(email_service, "Email Service")

    System_Boundary(registry_system, "Companies Registry") {
        Container(api, "Web API", "Clojure / Reitit", "Versioned HTTP API. Authenticates requests, enforces authorisation, routes commands and queries to the application core.")
        Container(app_core, "Application Core", "Clojure — Modular Monolith", "Contains all domain modules: Draft, RegistrationApplication, RegisteredCompany, Identity, Payment, Notifications, PublicRegister. Hosts in-process event bus and background job threads.")
        ContainerDb(db, "PostgreSQL", "Postgres 16", "Stores: event store, command ledger, outbox, audit log, process manager state, and all read-model projections.")
    }

    Rel(applicant, api, "HTTPS", "POST /v1/drafts, POST /v1/drafts/{id}/submit, ...")
    Rel(examiner, api, "HTTPS", "POST /v1/applications/{id}/request-amendment, ...")
    Rel(registrar, api, "HTTPS", "POST /v1/applications/{id}/approve, ...")
    Rel(public, api, "HTTPS", "GET /v1/companies, GET /v1/companies/{id}")

    Rel(api, identity_broker, "Validates JWT tokens, fetches JWKS", "HTTPS / OIDC")
    Rel(api, app_core, "Dispatches commands and queries", "in-process")
    Rel(app_core, db, "Reads and writes", "JDBC / HikariCP")
    Rel(app_core, payment_gateway, "Initiates payment session", "HTTPS API")
    Rel(app_core, address_validation, "Validates address at submission", "HTTPS API")
    Rel(app_core, email_service, "Publishes outbox events, relayed by outbox worker", "HTTPS API")
```

### Module Responsibilities

| Module | Bounded Context | Owns |
|--------|----------------|------|
| Draft | Draft | Draft aggregate; CreateDraft, AmendDraft, SubmitDraft, CancelDraft commands |
| RegistrationApplication | RegistrationApplication | Application aggregate; examination and decision workflow; Submission and Approval process managers |
| RegisteredCompany | RegisteredCompany | RegisteredCompany aggregate; director/address changes; strike-off and dissolution |
| Identity | Identity | Verified person records consumed from Identity Broker; person lookup |
| Payment | Payment boundary | Payment initiation and outcome recording; fee schedule |
| Notifications | Notifications | Notification read model; renders and dispatches email via outbox |
| PublicRegister | PublicRegister | Public-facing read model; company search and detail queries |

### Internal Communication

Commands and queries cross module boundaries only through defined ports
(ADR-0019). Modules do not call each other's internal functions. Domain events
published on the in-process bus are the only coupling between modules at
runtime (ADR-0015).

---

## Data Architecture

*How is data stored and how does it flow from the write side to the read side?*

```mermaid
flowchart TD
    CMD["Command\n(e.g. SubmitDraft)"] --> CL["Command Ledger\ncommands table\nIdempotency check (ADR-0004)"]
    CL --> DEC["Decider\n(pure function)"]
    DEC --> ES["Event Store\nevents table\nOptimistic concurrency (ADR-0017)"]
    ES --> OB["Transactional Outbox\noutbox_events table\n(ADR-0005)"]
    ES --> BUS["In-process Event Bus\n(ADR-0015)"]
    BUS --> PROJ["Projectors\nUpdate read models"]
    BUS --> PM["Process Managers\nprocess_instances table\n(ADR-0006, ADR-0024)"]
    BUS --> AL["Audit Writer\naudit_log table\n(ADR-0018)"]
    OB --> EXT["External Systems\n(Email Service, etc.)"]
    PROJ --> RM["Read Models\nread-model tables\n(ADR-0002)"]
```

### Schema Definitions

#### Event Store

The authoritative Register. Events are never updated or deleted (ADR-0001).

```sql
CREATE TABLE events (
    id              UUID        PRIMARY KEY,
    stream_id       UUID        NOT NULL,           -- aggregate instance ID
    stream_type     TEXT        NOT NULL,           -- "draft" | "registration-application" | "registered-company"
    position        BIGINT      NOT NULL,           -- sequence within this stream
    global_position BIGSERIAL,                      -- global ordering for projectors
    event_type      TEXT        NOT NULL,           -- e.g. "draft-submitted.v1"
    payload         JSONB       NOT NULL,
    metadata        JSONB       NOT NULL,           -- actor_id, causation_id, correlation_id, occurred_at
    UNIQUE (stream_id, position)
);
```

`position` is the optimistic concurrency token: an append with
`expected-position = N` fails if the stream has already reached position `N`
(ADR-0017). `global_position` is a monotonic sequence used by projectors to
track replay progress.

#### Command Ledger

Records every command processed. Enables idempotency: a command whose
`command_id` already exists returns the previously recorded outcome without
re-executing (ADR-0004).

```sql
CREATE TABLE commands (
    command_id      UUID        PRIMARY KEY,
    command_type    TEXT        NOT NULL,
    payload         JSONB       NOT NULL,
    outcome         JSONB,                         -- recorded result; NULL until processed
    actor_id        UUID        NOT NULL,
    correlation_id  UUID        NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL,
    processed_at    TIMESTAMPTZ
);
```

#### Transactional Outbox

Events to be published to external systems are written in the same transaction
as the domain events. A background worker relays them (ADR-0005).

```sql
CREATE TABLE outbox_events (
    id              BIGSERIAL   PRIMARY KEY,
    event_id        UUID        NOT NULL REFERENCES events(id),
    topic           TEXT        NOT NULL,          -- routing key for the target system
    payload         JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    published_at    TIMESTAMPTZ                    -- NULL until relayed
);
```

#### Audit Log

A separate, human-readable record of every Registry decision. Written by an
audit writer subscribing to the in-process bus, relayed via the outbox to
ensure it survives crashes (ADR-0018). Designed for external auditors who
should not need to understand event sourcing.

```sql
CREATE TABLE audit_log (
    id              BIGSERIAL   PRIMARY KEY,
    actor_id        UUID        NOT NULL,
    actor_role      TEXT        NOT NULL,          -- "examiner" | "registrar" | "applicant" | "system"
    action          TEXT        NOT NULL,          -- e.g. "approved-registration-application"
    target_type     TEXT        NOT NULL,          -- "registration-application" | "registered-company"
    target_id       UUID        NOT NULL,
    description     TEXT        NOT NULL,          -- human-readable prose for auditors
    causation_id    UUID        NOT NULL,          -- traces back to the originating command
    correlation_id  UUID        NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL
);
```

#### Process Manager State

Persists the state of in-flight workflow instances so they survive crashes and
restarts (ADR-0024).

```sql
CREATE TABLE process_instances (
    id              UUID        PRIMARY KEY,       -- stable across restarts
    process_type    TEXT        NOT NULL,          -- "submission" | "approval"
    current_step    TEXT        NOT NULL,
    inputs          JSONB       NOT NULL,          -- events received so far
    commands_issued JSONB       NOT NULL,          -- commands already dispatched (for idempotency)
    outcomes        JSONB       NOT NULL,          -- recorded results of issued commands
    started_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    completed_at    TIMESTAMPTZ
);
```

#### Read Models

Read models are disposable projections rebuilt from the event store (ADR-0002,
ADR-0023). They are never the source of truth.

| Table | Purpose | Updated By |
|-------|---------|-----------|
| `company_register` | Public-facing register search and detail | PublicRegister projector |
| `application_queue` | Examiner and Registrar work queue | RegistrationApplication projector |
| `draft_view` | Applicant's in-progress draft | Draft projector |
| `company_particulars` | Full company details including director history | RegisteredCompany projector |
| `notification_queue` | Pending outbound notifications | Notifications projector |

### Write-to-Read Data Flow

```
Command received
  → Idempotency check against command ledger
  → Decider loads event stream (events table)
  → Decider produces new events
  → Append events + write outbox + record command outcome (single transaction)
  → In-process bus delivers events to:
      • Projectors → update read model tables
      • Process managers → update process_instances, dispatch next commands
      • Audit writer → insert into audit_log
  → Outbox worker (background thread) relays to external systems
```

---

## Security Architecture

*How are actors authenticated, authorised, and audited?*

### Claims Flow

Every request to the Web API must carry a signed JWT issued by the Identity
Broker. The API validates the token signature against the broker's JWKS
endpoint, extracts the claims, and attaches them to the request context. No
session state is held server-side.

```mermaid
sequenceDiagram
    actor User
    participant IB as Identity Broker
    participant API as Web API
    participant Core as Application Core

    User->>IB: Authenticate (OIDC flow)
    IB-->>User: Signed JWT with claims
    User->>API: HTTP request + Bearer JWT
    API->>IB: Fetch JWKS (cached)
    API->>API: Validate signature, expiry, audience
    API->>API: Extract claims (sub, roles, verified_person_id, org_id)
    API->>Core: Dispatch command with ClaimsPrincipal
    Core->>Core: RBAC → ABAC → FLS → RLS checks
    Core-->>API: Result
    API-->>User: HTTP response
```

### JWT Claim Structure

| Claim | Type | Description |
|-------|------|-------------|
| `sub` | UUID | Unique identifier for the authenticated user |
| `roles` | `[]string` | Registry roles: `applicant`, `examiner`, `registrar`, `admin` |
| `verified_person_id` | UUID | ID of the verified person record in the Identity context |
| `org_id` | UUID | Organisation the user is acting on behalf of (applicants only) |
| `email` | string | Delivery address for notifications |

### Authorisation Layers

Authorisation is applied in sequence (ADR-0020). A request must pass all
applicable layers.

```mermaid
flowchart TD
    REQ["Incoming Command\n+ ClaimsPrincipal"] --> RBAC

    RBAC["1. RBAC\nRole-Based Access Control\nDoes this role exist and permit this command type?"]
    RBAC -->|Denied| REJECT["Rejected\n403 Forbidden"]
    RBAC -->|Permitted| ABAC

    ABAC["2. ABAC\nAttribute-Based Access Control\nDoes the actor own or have authority over this resource?\ne.g. Applicant can only amend their own draft"]
    ABAC -->|Denied| REJECT
    ABAC -->|Permitted| STATE

    STATE["3. State Machine Guard\nIs this command legal in the aggregate's current state?\ne.g. SubmitDraft only valid from :active state"]
    STATE -->|Illegal| REJECT
    STATE -->|Legal| FLS

    FLS["4. Field-Level Security (FLS)\nStrip or substitute fields by data classification\nPublic fields → all callers\nRestricted → Registry staff only\nConfidential → named roles only\nSealed → admin + authorised officers only"]
    FLS --> RLS

    RLS["5. Row-Level Security (RLS)\nFilter result sets to rows the actor may see\ne.g. Examiner sees only applications in their queue"]
    RLS --> ALLOW["Allowed\nCommand executed / Query returned"]
```

FLS is driven by classification metadata annotated on response schemas (ADR-0025).
The full field-by-field classification table is in [`07-business-rules.md`](07-business-rules.md)
under Group 14 (BR-DC-*).

#### RBAC — Role Permissions

| Command / Query | applicant | examiner | registrar | admin | system |
|---|---|---|---|---|---|
| CreateDraft, AmendDraft, SubmitDraft, CancelDraft | own only | — | — | — | — |
| RequestAmendment, MarkReadyForDecision | — | ✓ | — | — | — |
| ApproveApplication, RejectApplication | — | — | ✓ | — | — |
| StrikeOffCompany, DissolveCompany | — | — | ✓ | — | — |
| GET /v1/companies (public register) | ✓ | ✓ | ✓ | ✓ | — |
| GET /v1/companies/{id}/audit-log | — | — | — | ✓ | — |

#### ABAC — Ownership Rules

| Resource | Rule |
|----------|------|
| Draft | Only the `org_id` that created the draft may amend or cancel it |
| RegistrationApplication | Only the examiner assigned to an application may mark it ready for decision |
| RegistrationApplication | **Four-Eyes Rule (§13A):** The `verified_person_id` of the acting Registrar must not match the `verified_person_id` of the assigned Examiner on the same application. Checked at `ApproveApplication` and `RejectApplication`. Breach is rejected with reason `four-eyes-violation` and recorded in the audit log (BR-4E-001–BR-4E-004) |
| RegisteredCompany | Only a director with an active `verified_person_id` linked to the company may file changes |

### Data Classification and Log Controls

Data classification (ADR-0025) governs what each surface is permitted to reveal:

| Surface | Masking | Access |
|---------|---------|--------|
| Public API endpoints | FLS strips Restricted and above; only Public fields returned to unauthenticated callers | Open (unauthenticated) |
| Internal API endpoints | FLS strips fields above the caller's permitted classification level | Authenticated Registry staff |
| Application logs | Fields classified Restricted, Confidential, or Sealed replaced with `[RESTRICTED]`, `[CONFIDENTIAL]`, `[SEALED]` at log emission | Moderately restricted — operations/DevOps personnel only, controlled by log aggregation platform |
| Audit log | No masking — full fidelity required as the legal record | Extremely restricted — `admin` role and external auditors via `audit_reader` DB role only; no API endpoint exposes audit log rows |

#### Database Role Separation for Log Access

```
registry_app   — INSERT/SELECT/UPDATE on events, commands, outbox, read models, process_instances
                 INSERT on audit_log   (write-only; cannot read back)
                 No access to audit_log SELECT

audit_writer   — INSERT-only on audit_log (used by audit writer component)

audit_reader   — SELECT-only on audit_log (used by external auditors via direct DB session)
```

This means a compromised application process cannot read or modify audit log
rows, and cannot expose them through any API endpoint.

### Audit Trail as a Security Control

Every command execution — whether accepted or rejected — produces an audit log
entry containing the actor identity, role, action taken, target resource,
human-readable description, and timestamp. Entries are immutable. The audit log
is written transactionally with domain events and cannot be bypassed.

Auditors connect to the database directly using the `audit_reader` credential
and inspect `audit_log` rows without needing to understand the event store
schema (ADR-0018). Audit log entries contain full-fidelity values with no
masking.

### Transport Security

| Boundary | Control |
|----------|---------|
| Client → Web API | TLS 1.2+ enforced; HTTP Strict Transport Security header |
| Web API → Identity Broker | TLS; JWKS cache with short TTL; JWT audience claim validated |
| Web API → Payment Gateway | TLS; API key held in environment secret, not in code or config files |
| Web API → Address Validation | TLS; API key from environment |
| Application → Database | TLS; credentials from environment; least-privilege DB role per concern |
| Outbox Worker → Email Service | TLS; API key from environment |

### Secrets Management

No secrets are stored in code or committed configuration files. All credentials
(database password, payment gateway API key, email API key) are injected as
environment variables at runtime. In production this is satisfied by the
container orchestration platform's secret store (ADR-0014, ADR-0009 deferred).
