# Business Rules

Enumeration of every business rule in the micro companies registry, derived from [companies-registration-act.md](companies-registration-act.md).

**Business rules** are domain checks run after the state machine has accepted a command. They can reject a command even when the state machine says it is allowed. The state machine handles structural constraints (which commands are legal in which states); business rules handle substantive constraints (whether the command's content satisfies the law).

Transition numbers refer to [application-state-machine.md](application-state-machine.md).

---

## Active rules

| ID | Rule | CRA | Function | Applied at | Reused at |
|---|---|---|---|---|---|
| BR-002 | Application must state a company name | §2 | `name-stated?` | T2 | T6 |
| BR-003 | Application must name at least two proposed directors | §2 | `at-least-two-proposed-directors?` | T2 | T6 |
| BR-004 | Application must state a registered office address | §2 | `address-stated?` | T2 | T6 |
| BR-005 | Every proposed director must be a natural person | §3 | `all-proposed-directors-natural-persons?` | T2 | T6 |
| BR-006 | Every proposed director must be identity-verified by the Registry | §3 | `all-proposed-directors-identity-verified?` | T2 | T6 |
| BR-007 | A rejection must state a reason | §6 | `rejection-reason-stated?` | T7 | — |
| BR-008 | A company name must not already exist in the register | §8 (derived) | event-store unique index on `company-registered` names | T6 | — |
| BR-010 | Registered office address must be plausible and valid | §4, §5 | `registered-office-address-valid?` | T6 | — |

## Parked rules

These are known legal or operational requirements that are deliberately retained but not enforced in this project.

| ID | Rule | CRA | Why parked |
|---|---|---|---|
| BR-001 | Applicant must be a natural person | §1 | Requires an identity provider or trusted person registry before draft creation |
| BR-009 | Name reservation for an in-flight application | §8 (derived) | Requires a reservation workflow so a proposed name cannot be taken between submission and approval |

---

## Notes

**BR-001 — Applicant eligibility (§1)**
The Act permits registration only by natural persons. This requirement is retained so it is not forgotten, but it is parked for this project because enforcing it requires an identity provider or trusted person registry. The `X-User-Id` header is trusted and no applicant natural-person check is performed. A production implementation must enforce BR-001 at draft creation before accepting `create-registration-application`.

**BR-002, BR-003, BR-004 — Application requirements (§2)**
These three rules are checked twice: at submission (T2) to give the applicant early feedback, and again at approval (T6) because §5 requires the Registrar to verify compliance with the Act at the point of decision. BR-003 requires two proposed directors, not merely one proposed director. Checking again at T6 is defensive but correct — the §5 compliance check is the legally significant one.

**BR-005 — Proposed director must be a natural person (§3)**
Checked at submission (T2) and approval (T6). In this project, Registry identity verification proves both identity and natural-person status. BR-005 remains a separate rule because §3 separately requires the proposed director to be a natural person, even though the same verification evidence satisfies BR-005 and BR-006.

**BR-006 — Proposed director identity verification (§3)**
The Registry performs identity verification; the applicant does not. Proposed directors must be pre-registered and verified with the Registry before being named in an application. BR-006 is checked at both T2 (submission) and T6 (approval): at T2 to give the applicant early feedback before the application enters the examination queue; at T6 because §5 requires full compliance at the point of decision, and a proposed director's verified status could theoretically change between submission and approval. In this project, proposed directors carry an `identity-verified` flag set by a separate Registry process, and that verified status proves natural-person status.

**BR-007 — Rejection reason (§6)**
The Act requires the Registrar to state the reason for rejection. A rejection command with a blank or missing reason must be refused. This protects the applicant's right to know why their application failed.

**BR-008 — Company name uniqueness (§8, derived)**
The Act does not explicitly state that company names must be unique, but §8 makes the register the authoritative legal record. Two registered companies with identical names would make the register ambiguous — which record is authoritative? Uniqueness is therefore a derived requirement of §8.

It is enforced at approval (T6), because approval and registration are one atomic transition in this project. The durable enforcement point is the event store: the `registration_events` table has a unique partial index over `company-registered` event names. Any async register projection is only a read model and must not be used as the source of truth for this rule.

**BR-009 — Name reservation (§8, derived, parked)**
This project does not model name reservation. A production registry usually needs a reservation workflow so a proposed name cannot be taken by another application between submission and approval. That workflow is parked for a later project; until then, the authoritative guarantee is BR-008 at the atomic approval/registration step.

**BR-010 — Registered office address validity (§4, §5)**
The application must state a registered office address at submission (BR-004), but the Registry examines whether the address is plausible and valid before approval. This includes checks such as required address parts being present, the address not being obviously fake, and the address being usable as a registered office under Registry policy. BR-010 is checked at T6 because it is part of the examination and approval decision, not merely form completion.
