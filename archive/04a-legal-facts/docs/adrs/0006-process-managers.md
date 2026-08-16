# ADR-0006: Process Managers For Cross-Aggregate Workflows

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

Some workflows cross aggregate boundaries. For example, approval records a
decision about a registration application, while registration creates a
registered company with its own identity and lifecycle.

## Decision

Use process managers for cross-aggregate workflows. Process managers react to
events, issue commands, record their own progress, and retry safely after
failure.

The approval-to-registration flow is coordinated by a process manager:

1. Observe `RegistrationApplicationApproved`.
2. Issue `CreateRegisteredCompany`.
3. Wait for `RegisteredCompanyCreated`.
4. Notify the Applicant only after the company exists.

## Consequences

- Aggregates own only their own facts.
- Workflow progress is auditable and recoverable.
- Registration notification is never sent before `RegisteredCompanyCreated`.
- Process-manager commands must use stable command IDs and the command ledger.
