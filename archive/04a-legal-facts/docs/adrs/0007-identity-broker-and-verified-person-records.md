# ADR-0007: Identity Broker And Verified-Person Records

*Status: Accepted.*
*Date: 2026-05-26.*

## Context

Every natural person acting in the Registry system or appearing in company data
must have a verified identity flag or record. The project does not perform the
identity-proofing ceremony itself.

## Decision

Use a trusted OIDC identity broker for protected Registry users. The broker
must supply a verified natural-person identity claim accepted by the Registry.

Use verified-person records for natural persons named in company data, such as
proposed directors, directors, and Authorised Officers.

Identity proofing, proposed-director outreach, revocation, and re-verification
are parked for future work.

## Consequences

- Commands can rely on verified identity facts without modelling proofing.
- Applicant, director, and Authorised Officer identity checks are consistent.
- Future identity workflows can be added without weakening current rules.
- Tests must reject protected actions without verified natural-person identity.
