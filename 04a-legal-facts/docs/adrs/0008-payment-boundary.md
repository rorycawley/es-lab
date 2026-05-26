# ADR-0008: Payment Belongs To Submission Boundary

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

The filing fee is due at submission. A registration application must not be
created unless the fee has been paid.

Payment gateway implementation is outside this proof-of-concept contract.

## Decision

Treat payment as part of the `SubmitDraft` flow rather than as a separate
aggregate or workflow.

Submission validation runs before payment is requested. If validation fails,
no fee is presented or taken. If payment succeeds and application creation
fails due to a system failure, the fee is refunded.

## Consequences

- Tests can treat payment as a boundary fact without integrating a gateway.
- Duplicate submission commands must not take a second payment.
- The payment story remains traceable through submission acceptance criteria.
