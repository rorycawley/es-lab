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
| `00-companies-registration-act.md` | Defines legal facts and obligations |
| `07-business-rules.md` | Turns the Act into enforceable rules |
| `08-user-stories.md` | Describes observable user/system behaviour |
| `03-business-drivers.md` | Explains the pressures driving the work |
| `docs/adrs/` | Records structural architecture decisions |
| `06-architectural-characteristics.md` | Defines system-wide qualities and fitness functions |

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
| Privacy and disclosure control | The public Register is open, but not every internal fact is public. | Public read models expose only Public-classified fields. Internal audit facts stay protected. |
| Data protection | Sensitive personal data (director home addresses, identity documents, payment references) must not be disclosed beyond their permitted classification level. | Every data field and document type carries a classification label. FLS enforces classification at query time. Application logs mask Restricted and above. |
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

### Privacy, Disclosure Control, and Data Protection

The Register is open to public inspection, but public inspection is not the same
as full internal transparency. Public read models expose only Public-classified
fields: registration number, company name, registered office, status, date of
registration, and director information defined by the current public search
stories.

Internal facts such as command IDs, causation IDs, correlation IDs, payment
references, session identifiers, operational exceptions, and process-manager
state are Restricted-classified operational data, not public Register data.

Sensitive personal data - director home addresses where used as registered
office, identity document details, and payment reference numbers - is
Confidential-classified and must not appear in public or internal API responses
beyond the named roles permitted to see it.

**Logging and the audit log are distinct surfaces with different rules:**

- The **audit log** carries full-fidelity data with no masking. It is the legal
  record of Registry decisions and must be complete. Access is extremely
  restricted: only the `admin` role and external auditors via a dedicated
  read-only database connection using the `audit_reader` DB role.
- **Application logs** are operational output consumed by infrastructure and
  DevOps teams. Any field classified Restricted or above must be replaced with
  a classification marker (`[RESTRICTED]`, `[CONFIDENTIAL]`, `[SEALED]`) at
  the point of log emission. Application logs must never contain plaintext
  values for personal data, identity details, or payment references. Access
  to application logs is moderately restricted: operations/DevOps personnel
  only, separate from domain staff access.

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
Each has a stable ID used in user story traceability metadata.

| ID | Fitness Function | Characteristic Protected | Expected Check |
|----|------------------|--------------------------|----------------|
| FF-001 | No company exists without `RegisteredCompanyCreated`. | Legal integrity, consistency | Query company existence from events and prove approval/read-model rows alone are insufficient. |
| FF-002 | Read models can be rebuilt from events. | Rebuildability | Drop or clear projection state, replay events, and compare expected public query results. |
| FF-003 | Events are append-only and must never be updated or deleted. | Legal integrity, auditability | Tests or database permissions prevent event update/delete paths. |
| FF-004 | Duplicate command IDs return the recorded outcome. | Idempotency | Re-run commands with the same command ID and assert no duplicate side effects. |
| FF-005 | Multiple events may share a causation ID. | Auditability, process correctness | Persist a command that legitimately causes multiple events with the same causation ID. |
| FF-006 | Every Decider honours its state machine spec. | Controlled decision-making | For each aggregate, walk every `[from-state command]` entry in the EDN spec in `07-business-rules.md`; assert the Decider accepts the command and the resulting state matches the declared target; assert every undeclared `[from-state command]` pair is rejected. |
| FF-007 | Registration notification is sent only after `RegisteredCompanyCreated`. | Legal certainty, operational correctness | Prove `RegistrationApplicationApproved` alone does not trigger registration notification. |
| FF-008 | Public search returns struck-off and dissolved companies with status. | Public trust, inspectability | Search public Register after status changes and assert companies remain visible with status. |
| FF-009 | Register read model is written only by projectors. | Consistency | Code structure or tests prevent direct application writes to projection tables. |
| FF-010 | Parked concerns do not leak into active acceptance criteria. | Scope control | Document consistency check flags active criteria for parked features. |
| FF-011 | No Restricted, Confidential, or Sealed field appears as a plaintext value in application logs. | Data protection | Emit commands and events through the logging path in a test environment; assert every field classified above Public is replaced with its classification marker. |
| FF-012 | Public read model queries return only Public-classified fields. | Privacy and disclosure control, data protection | Call every public query endpoint and assert the response contains no Restricted or Confidential fields as defined in the data classification rules (BR-DC-*). |
| FF-013 | The audit log is not accessible via any application API endpoint. | Data protection | Assert no API route exists that returns audit log rows; assert `audit_log` is not readable by the application DB role used for command handling or read models. |
| FF-014 | The four-eyes rule is enforced: a Registrar who also examined the application cannot approve or reject it. | Security, controlled decision-making | Attempt to issue `ApproveApplication` and `RejectApplication` as a Registrar whose `verified_person_id` matches the assigned Examiner on that application; assert both commands are rejected with reason `four-eyes-violation` and that the attempt is recorded in the audit log. |


## Traceability Matrix

This matrix maps each architectural characteristic to the ADRs that implement
it, the user stories that exercise it, and the fitness functions that verify it.
The reverse lookup - from user story or test back to characteristic - is in the
`Characteristics:` and `Fitness functions:` fields of each user story.

| Characteristic | Implementing ADRs | Key User Stories | Fitness Functions |
|----------------|------------------|-----------------|------------------|
| Legal integrity | ADR-0001, ADR-0010, ADR-0017 | US-RE-002, US-AP-007, US-RE-004, US-RE-005 | FF-001, FF-003 |
| Auditability | ADR-0001, ADR-0018 | All stories that produce events | FF-003, FF-005 |
| Data consistency | ADR-0001, ADR-0002, ADR-0011, ADR-0017 | US-RE-002, US-PB-001, US-PB-002 | FF-001, FF-002, FF-009 |
| Idempotency | ADR-0004, ADR-0017, ADR-0024 | US-AP-007, US-RE-002, US-SY-001, US-SY-002 | FF-004 |
| Fault tolerance | ADR-0005, ADR-0006, ADR-0013, ADR-0024 | US-AP-010, US-SY-001, US-SY-002, US-SY-003 | FF-002, FF-004 |
| Observability | ADR-0004, ADR-0018 | All stories that produce events | FF-005 |
| Security | ADR-0007, ADR-0020 | US-AP-016, US-AP-001, US-EX-002, US-RE-002 | FF-014 |
| Privacy and disclosure control | ADR-0020 | US-PB-001, US-PB-002, US-PB-003, US-AP-008 | FF-012 |
| Rebuildability | ADR-0002, ADR-0023 | US-PB-001, US-PB-002 | FF-002 |
| Data protection | ADR-0020, ADR-0025 | All stories that expose personal data | FF-011, FF-012, FF-013 |
| Controlled decision-making | ADR-0010, ADR-0020 | US-EX-001, US-EX-002, US-RE-001 | FF-006, FF-014 |
| Scope control | - | All stories | FF-010 |
| Testability | ADR-0010, ADR-0019 | All stories | FF-006 |

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
