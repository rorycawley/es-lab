# ADR-0025: Data Classification Model

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

The Registry handles data at different sensitivity levels. Some data must be
publicly inspectable under the Act (company name, registration number, director
names). Some data is confidential personal information (director home addresses,
identity documents). Some data is internal operational metadata (command IDs,
examiner notes, process manager state). Without an explicit classification
model, disclosure decisions are made ad hoc, FLS and RLS rules have no
principled basis, and logging may leak sensitive data to operational tooling.

Additionally, the audit log and application logs have fundamentally different
requirements: the audit log must be complete and unmasked because it is the
legal record of Registry decisions; application logs must mask sensitive data
because they are consumed by infrastructure tooling and may be shipped to
third-party log aggregators.

## Decision

### 1. Four-level taxonomy

Every data field and document type is assigned one of four classification
labels, defined in the business rules (BR-DC-001, BR-DC-002):

| Label | Meaning |
|-------|---------|
| `Public` | Open to all under the Act |
| `Restricted` | Internal Registry staff only |
| `Confidential` | Named roles only; sensitive personal data |
| `Sealed` | Court order or regulatory hold |

Classification is a design-time property of the schema, not a runtime
computation. It is defined in the business rules document (Group 14) and
enforced in code via the FLS layer in the authorisation pipeline (ADR-0020).

### 2. Classification enforcement at query time

The FLS layer (ADR-0020) strips or substitutes fields according to the caller's
claims before the response is serialised. Public fields are returned to all
callers. Restricted fields are returned only to Registry staff roles. Confidential
fields are returned only to the named roles in the classification table.
Sealed fields are returned only to `admin` and designated authorised officers.

A field's classification is encoded as metadata on the response schema
(e.g. a `^:classification :confidential` annotation on Clojure maps used as
response shapes). The FLS interceptor reads this metadata and strips fields
accordingly. This keeps classification logic out of individual handler
functions.

### 3. Log masking for application logs

Application logs are operational output consumed by infrastructure and DevOps
tooling. They may be shipped to third-party log aggregators and are accessible
to personnel outside the domain (infrastructure engineers, SREs). They are not
a legal record.

Any value with classification Restricted, Confidential, or Sealed must be
replaced with its classification marker at the point of emission into the
application log. Masking is applied by a logging interceptor that wraps
structured log maps before they are serialised. Plaintext values for personal
data, identity documents, payment references, and similar Confidential fields
must never appear in application logs.

Access to application logs is moderately restricted: operations and
infrastructure personnel only, controlled outside the Registry application
via the log aggregation platform's access controls.

### 4. Audit log: full fidelity, extremely restricted access

The audit log is the legal record of Registry decisions (ADR-0018). It must
record full-fidelity values — no masking is applied. An audit log entry that
masks the director's home address is useless to an auditor investigating whether
a registration decision was lawful.

Access to the audit log is controlled at the database level, not the
application level:

- The `audit_writer` DB role has INSERT-only access to `audit_log`.
- The `audit_reader` DB role has SELECT-only access to `audit_log`.
- The application's primary DB role (`registry_app`) has no access to
  `audit_log` at all.
- No API endpoint returns rows from `audit_log`. Auditors connect directly
  via a read-only database session using the `audit_reader` role.

This separation means that even a compromised application process cannot
expose audit log rows through the API, and cannot alter or delete audit
entries.

### 5. Document lifecycle

Document classification may be promoted (Restricted → Public) at defined
lifecycle transitions, recorded as an event. Classification may never be
demoted to a lower level. The promotion is recorded in the event store so the
classification history of every document is permanent and auditable.

## Consequences

- FLS logic has a clear, schema-level basis rather than ad hoc per-handler
  checks.
- Application logs are safe to ship to third-party log aggregators without
  pre-processing.
- Audit log integrity is enforced at the database permission level, not
  reliant on application-level controls.
- A new data field or document type added to the codebase without a
  classification annotation will fail the build (FF-012 catches unclassified
  Public-endpoint fields; a linter check catches missing annotations).
- Adding a new role that should access Confidential fields requires an
  explicit change to the classification table in business rules, an ADR
  amendment, and a code change — there is no implicit escalation path.
- External auditors require a direct database connection and the `audit_reader`
  credential. This is a procedural overhead worth accepting for the access
  control guarantee it provides.
