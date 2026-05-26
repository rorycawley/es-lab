# Approach

This project is a domain-design exercise for a small fictional companies
registry. The purpose is to make the legal facts explicit before implementation:
the Act defines the legal model, the business rules translate it into enforceable
rules, and the user stories describe observable behaviour. Business drivers,
architectural characteristics, and ADRs provide the wider design context that
does not fit naturally into user stories.

## Modelling stance

- The Register is the authoritative event-backed record of registered
  companies.
- Read models are disposable projections used for search and inspection.
- Commands express user or system intent in PascalCase domain language.
- Events record facts that have already happened and are never altered.
- Approval and registration are separate facts, automatically chained by a
  process manager.
- `RegisteredCompanyCreated` is the legal existence fact for a company.

## Current scope

The current contract covers draft preparation, submission with payment,
examination, requisitions, approval or rejection, registered-company creation,
thin post-registration obligations, public register search, and basic system
processes.

## Supporting Architecture Documents

- [Business drivers](BUSINESSDRIVERS.md)
- [Architectural characteristics](ARCHITECTURALCHARACTERISTICS.md)
- [Architectural decision records](adrs/README.md)

## Parked scope

Several important registry concerns are deliberately parked because they need
separate legal and product design:

- Name reservation and name locking
- Identity-proofing ceremonies and proposed-director outreach
- Identity verification revocation
- Full outstanding-obligations modelling for dissolution
- Rich historical person search
- Payment gateway implementation details
- Notification preferences

## References

- https://news.ycombinator.com/item?id=48272984
- https://www.youtube.com/watch?v=A_e90lKVUwo
