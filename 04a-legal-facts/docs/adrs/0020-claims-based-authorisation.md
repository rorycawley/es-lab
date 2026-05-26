# ADR-0020: Claims-Based Authorisation with RBAC, ABAC, Field-Level and Row-Level Security

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

Different roles (Applicant, Examiner, Registrar, Authorised Officer, Public)
may perform different actions and see different data. Some access rules depend
on ownership (an Applicant may only cancel their own draft), some on data
classification (internal audit fields vs. public Register fields), and some on
which rows are visible (an Examiner sees only applications assigned to them).
OIDC authentication (ADR-0007) establishes identity; this ADR establishes what
that identity is permitted to do and see.

## Decision

Use claims-based authorisation with four layers applied in sequence:

**1. Role-based access control (RBAC)**
Coarse-grained gate at the API endpoint and command boundary. Each role has a
set of permitted commands. A command not in the role's permission set is
rejected before the FSM or Decider is reached. Roles are extracted from the
trusted OIDC session claims — never from the request body.

**2. Attribute-based access control (ABAC)**
Ownership and attribute checks applied inside the command handler, after the
FSM gate. Examples: an Applicant may only cancel a draft they created; an
Examiner may only raise requisitions on applications assigned to them; a
Registrar may only approve in `ReadyForDecision` state (covered by FSM, but
ownership attributes add an additional layer for cross-ownership protection).

**3. Field-level security**
Response shaping based on the caller's role. The same underlying data is
projected differently per role. Example: an Applicant viewing their application
does not see the Examiner's identity or internal case notes; an Examiner sees
those fields. The Register read model has a public projection and an
internal-only projection.

**4. Row-level security**
Filters applied at the query boundary. Example: an Examiner's work queue
returns only applications in `Submitted` state; their assigned queue returns
only applications assigned to them. Applied in query adapters, not in the
domain.

## Consequences

- Authorisation logic is testable: claims are plain data, role checks are pure
  functions, and ownership checks run on domain state.
- Role changes do not require code changes: roles and permitted commands are
  configurable through claims issued by the identity broker.
- Four layers of authorisation must all be defined, tested, and maintained.
  Test matrix covers each role × each command × each field visibility × each
  row filter.
- Field-level and row-level security must be defined explicitly per response
  type. No implicit "return everything" responses.
- Claims are authoritative. A claim that a user holds the Registrar role is
  trusted; the application does not verify it against a local user table.
  Claim revocation is handled at the identity broker.
