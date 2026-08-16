# ADR-0010: Finite State Machines As Aggregate State Model

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

Each aggregate (Draft, RegistrationApplication, RegisteredCompany) undergoes
a defined set of state transitions. Without an explicit model, commands are
gated by ad-hoc conditional logic scattered across handlers, which is hard to
test, hard to audit, and easy to get wrong.

## Decision

Define every aggregate's permitted transitions as an explicit finite state
machine: an EDN map from `[from-state command]` to `to-state`. The FSM map is
the authoritative statement of what is allowed. All other transition logic is
derived from it.

The FSMs are embedded in `07-business-rules.md` as EDN code blocks and mirrored
in the implementation as data structures. A fitness function walks every entry
in the FSM spec and asserts the Decider accepts the command and lands on the
declared target state; and that every undeclared pair is rejected.

## Consequences

- Invalid commands are rejected at the FSM boundary before business rules run,
  giving clear, attributable error messages.
- The FSM spec is independently testable and readable without understanding
  business rule detail.
- Aggregate complexity is visible: a large FSM signals that an aggregate should
  be split.
- The documentation FSM and the implementation FSM cannot silently diverge -
  the fitness function enforces agreement.
- Terminal states are computable from the FSM: any state that appears only as
  a target, never as a source.
