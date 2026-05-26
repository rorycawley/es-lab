# Architectural Characteristics

*Status: Draft.*

This document captures the cross-functional requirements that shape the system.
User stories describe behaviour. ADRs record structural decisions. This document
defines the architectural characteristics the implementation must preserve.

These characteristics are not optional polish. They are the physics of the
software: they constrain design, implementation, testing, and operations.

## Relationship To Other Documents

| Document | Purpose |
|----------|---------|
| `companies-registration-act.md` | Defines legal facts and obligations |
| `business-rules.md` | Turns the Act into enforceable rules |
| `user-stories.md` | Describes observable user/system behaviour |
| `BUSINESSDRIVERS.md` | Explains the pressures driving the work |
| `docs/adrs/` | Records structural architecture decisions |
| `ARCHITECTURALCHARACTERISTICS.md` | Defines system-wide qualities and fitness functions |

## Characteristics

| Characteristic | Why It Matters | System Constraint |
|----------------|----------------|-------------------|
| Legal integrity | Registry records create and evidence legal facts. | Legal facts must be recorded as immutable events. |
| Auditability | Registry decisions must be explainable after the fact. | Events must include actor identity, timestamp, causation ID, and correlation ID. |
| Data consistency | The Register must not be contradicted by disposable query state. | The event-backed Register is authoritative; read models are derived. |
| Idempotency | Users, schedulers, process managers, and publishers can retry. | Commands must use a command ledger keyed by command ID. |
| Fault tolerance | Publication, notification, projection, and process management can fail independently. | Persist facts first; recover with outbox, replay, retries, and process-manager state. |
| Observability | Operators must understand where a workflow is and why it stopped. | Workflows, commands, events, and process-manager state need traceable IDs and visible status. |
| Security | Only the right verified natural person or Registry role may act. | Identity claims come from a trusted broker; role checks happen before commands enter Deciders. |
| Privacy and disclosure control | The public Register is open, but not every internal fact is public. | Public read models expose only intended Register data. Internal audit facts stay protected. |
| Rebuildability | Derived state can be lost or corrupted. | Read models must be rebuildable from event streams. |
| Scope control | This is a proof-of-concept micro-registry. | Parked concerns must not leak into active acceptance criteria or implementation. |
| Testability | Architectural qualities must not depend on manual review. | Mandatory fitness functions must run beside feature tests. |

## Design Implications

### Legal Integrity And Auditability

- Events are append-only.
- Events must never be updated or deleted.
- Every event records who caused it, when it happened, what command caused it,
  and what wider workflow/request it belongs to.
- Corrective action records new facts; it does not rewrite old facts.

### Data Consistency

- The Register is the event-backed set of `RegisteredCompany` streams.
- `RegisteredCompanyCreated` is the company existence fact.
- Read models are eventually consistent and disposable.
- Any workflow that needs ground truth must query the authoritative write-side
  facts, not the read model.
- Read-model constraints are defensive projection protection only.

### Fault Tolerance And Recoverability

- Persist domain facts before publishing messages.
- Use a transactional outbox for publication.
- Treat publication as at-least-once.
- Consumers, process managers, and projectors must be idempotent.
- Process managers must record their own state so they restart in the correct
  workflow position.
- If a read model is destroyed, the system must rebuild it from events.
- If a dependency is unavailable, the system must either reject the command
  before recording facts or record a clear operational exception path.

### Observability

At minimum, implementation should expose enough information to answer:

- Which command caused this event?
- Which external request or workflow does this command belong to?
- Which actor caused this action?
- Which process manager is waiting, retrying, completed, or failed?
- Which outbox events are pending publication?
- Which projection position has each projector reached?
- Why was a command rejected?

### Security

- Protected users authenticate through a trusted OIDC identity broker.
- The broker must supply a verified natural-person identity claim.
- Actor identity is extracted from the trusted session, not from request bodies.
- Role-based access control is enforced before a command reaches a Decider.
- Public endpoints expose only public Register information.
- Internal audit, command, and process-manager state is not public Register data.

### Privacy And Public Disclosure

The Register is open to public inspection, but public inspection is not the same
as full internal transparency. Public read models should expose the public
company record: registration number, company name, registered office, status,
date of registration, and director information defined by the current public
search stories.

Internal facts such as command IDs, causation IDs, correlation IDs, payment
references, session identifiers, operational exceptions, and process-manager
state are operational/audit data, not public Register data.

### Scope Control

Parked concerns must be visible but inactive. This protects the proof of concept
from growing into a full registry accidentally.

Parked concerns include:

- name reservation and name locking
- identity-proofing ceremonies and proposed-director outreach
- identity verification revocation and re-verification
- full outstanding-obligations modelling for dissolution
- rich historical person/director search
- payment gateway implementation details
- notification preferences
- Supervisor workflows such as Examiner reassignment
- final production deployment model

## Mandatory Fitness Functions

These fitness functions are mandatory from the first implementation slice.

| Fitness Function | Characteristic Protected | Expected Check |
|------------------|--------------------------|----------------|
| No company exists without `RegisteredCompanyCreated`. | Legal integrity, consistency | Query company existence from events and prove approval/read-model rows alone are insufficient. |
| Read models can be rebuilt from events. | Rebuildability | Drop or clear projection state, replay events, and compare expected public query results. |
| Events are append-only and must never be updated or deleted. | Legal integrity, auditability | Tests or database permissions prevent event update/delete paths. |
| Duplicate command IDs return the recorded outcome. | Idempotency | Re-run commands with the same command ID and assert no duplicate side effects. |
| Multiple events may share a causation ID. | Auditability, process correctness | Persist a command that legitimately causes multiple events with the same causation ID. |
| Every Decider honours its state machine spec. | Controlled decision-making | For each aggregate, walk every `[from-state command]` entry in the EDN spec in `business-rules.md`; assert the Decider accepts the command and the resulting state matches the declared target; assert every undeclared `[from-state command]` pair is rejected. |
| Registration notification is sent only after `RegisteredCompanyCreated`. | Legal certainty, operational correctness | Prove `RegistrationApplicationApproved` alone does not trigger registration notification. |
| Public search returns struck-off and dissolved companies with status. | Public trust, inspectability | Search public Register after status changes and assert companies remain visible with status. |
| Register read model is written only by projectors. | Consistency | Code structure or tests prevent direct application writes to projection tables. |
| Parked concerns do not leak into active acceptance criteria. | Scope control | Document consistency check flags active criteria for parked features. |

## Dependency Failure Expectations

| Dependency | Expected Behaviour |
|------------|-------------------|
| Database unavailable | Commands fail before facts are recorded; no partial legal facts are created. |
| Message broker unavailable | Events remain persisted and pending in the outbox; publication resumes later. |
| Projector failure | Legal facts remain safe; read model catches up from last projection position. |
| Notification failure | Notification retries without changing legal state. |
| Identity broker unavailable | Protected login and commands fail closed; public Register inspection remains available where possible. |
| Address validation unavailable at approval | The system follows BR-AP-009: record unavailability and require explicit Registrar confirmation before approval proceeds. |
| Payment gateway unavailable | Submission cannot complete; no registration application is created unless payment succeeds. |
| Deployment/cloud network interruption | No legal facts are lost; recovery depends on durable event store, outbox, and process-manager state. The exact deployment response is deferred to the deployment ADR. |

## Open Questions

No open architectural-characteristic questions at this point. New questions
should be resolved into this document, an ADR, or parked scope.
