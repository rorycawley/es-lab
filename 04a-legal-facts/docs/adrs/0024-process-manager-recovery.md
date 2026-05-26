# ADR-0024: Process Manager Recovery

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

Process managers coordinate workflows that span multiple aggregate boundaries
and may involve external systems (ADR-0006). Examples: the approval process
manager reacts to `RegistrationApplicationApproved`, issues
`CreateRegisteredCompany`, then triggers the registration notification. If the
process manager crashes between steps, it must resume from the correct position
rather than restarting from scratch (which would cause duplicate side effects)
or aborting (which would leave the workflow incomplete).

## Decision

Every process manager persists its own state transactionally with each step.
The state record includes: the process instance ID, the current step, the
inputs received, the commands already issued, and any intermediate outcomes.
Steps are idempotent: reissuing a command with the same command ID returns the
previously recorded outcome (ADR-0004) rather than repeating side effects.

On application restart, in-flight process manager instances are loaded from the
database and continued from their last recorded step.

Process manager state is modelled as an explicit state machine. Steps are named
transitions, not implicit progress counters.

## Consequences

- Process managers survive crashes and restarts. No workflow is silently
  abandoned.
- Idempotency requirements cascade: every command issued by a process manager
  must carry a stable, deterministic command ID derived from the process
  instance and step.
- Process manager state is visible and queryable. Stuck or failed instances
  can be identified and manually resolved without inspecting application logs.
- Each process manager adds a state table. For a small number of process
  managers this is negligible overhead.
- Process manager recovery is verified by fitness function: crash the process
  mid-workflow and assert the correct outcome after restart.
