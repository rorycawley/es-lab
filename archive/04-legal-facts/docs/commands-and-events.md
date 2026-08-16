# Commands and Events

Derived from [application-state-machine.md](application-state-machine.md) and [business-rules.md](business-rules.md). Every row defines part of the command/event contract for a future implementation.

---

## Commands

| Command | From state | Actor | Required fields | Business rules checked | Emits event(s) |
|---|---|---|---|---|---|
| `create-registration-application` | — | Applicant | `applicant-id` | — | `registration-application-created` |
| `submit-registration-application` | Draft | Applicant | `company-name`, `proposed-directors`, `registered-office-address` | BR-002, BR-003, BR-004, BR-005, BR-006 | `registration-application-submitted` |
| `begin-examination` | Submitted | Examiner | `examiner-id` | — | `examination-started` |
| `approve-registration-application` | Under examination | Registrar | `registrar-id` | BR-002, BR-003, BR-004, BR-005, BR-006, BR-008, BR-010 | `registration-application-approved`, `company-registered` |
| `reject-registration-application` | Under examination | Registrar | `registrar-id`, `rejection-reason` | BR-007 | `registration-application-rejected` |
| `withdraw-registration-application` | Submitted, Under examination | Applicant | `applicant-id` | — | `registration-application-withdrawn` |

---

## Events

| Event | Recorded by | Lifecycle effect | Fields recorded |
|---|---|---|---|
| `registration-application-created` | `create-registration-application` | — -> Draft | `application-id`, `applicant-id`, `created-at` |
| `registration-application-submitted` | `submit-registration-application` | Draft -> Submitted | `application-id`, `company-name`, `proposed-directors`, `registered-office-address`, `submitted-at` |
| `examination-started` | `begin-examination` | Submitted -> Under examination | `application-id`, `examiner-id`, `started-at` |
| `registration-application-approved` | `approve-registration-application` | Approval decision recorded; committed with `company-registered` | `application-id`, `registrar-id`, `approved-at` |
| `registration-application-rejected` | `reject-registration-application` | Under examination -> Rejected | `application-id`, `rejection-reason`, `registrar-id`, `rejected-at` |
| `registration-application-withdrawn` | `withdraw-registration-application` | Submitted or Under examination -> Withdrawn | `application-id`, `applicant-id`, `withdrawn-at` |
| `company-registered` | `approve-registration-application` | Under examination -> Registered | `application-id`, `company-name`, `registration-number`, `registered-at` |

---

## Notes

**`company-registered` is the most important event in the system.**
It is the moment a legal entity comes into existence and enters the register. The `registration-number` assigned here is permanent and must be unique across the entire register — it can never be reused, even if the company is later struck off or dissolved. Every downstream system that refers to this company uses this number. It appears for the first time in this event and nowhere before it.

**`rejection-reason` is legally mandated, not optional.**
CRA §6 requires the Registrar to state the reason when rejecting an application. `reject-registration-application` must be refused if `rejection-reason` is blank or absent. This is not a UX nicety — it is a legal protection for the applicant's right to know why their application failed.

**Approval emits two events atomically.**
The Registrar approves the application with `approve-registration-application`. CRA §5 says "upon approval, the company is registered and enters the Register", so the command emits `registration-application-approved` and `company-registered` in one transaction. There is no separate `register-company` command and no externally observable `Approved` state.

**BR-001 is parked, not enforced by `create-registration-application`.**
CRA §1 requires the applicant to be a natural person. This project keeps BR-001 in the rule catalogue, but does not check it because `X-User-Id` is trusted and there is no identity provider. A production registry must add BR-001 to `create-registration-application` before accepting a draft.

**`submit-registration-application` checks proposed director eligibility early.**
BR-005 (all proposed directors are natural persons) and BR-006 (all proposed directors identity-verified) are checked at submission as well as at approval. In this project, Registry identity verification proves natural-person status. Checking at submission gives the applicant early feedback before the application enters the examination queue. Checking again at approval is the legally significant moment (CRA §5), and ensures the requirement holds even if a proposed director's verified status changes between submission and decision.

**Address validity is an examination rule.**
BR-004 only checks that an address was supplied. BR-010 checks whether the registered office address is plausible and valid, and it runs at approval because the Registry examines the address before allowing registration.

**The required fields on a command are not the same as the fields recorded in its event.**
Commands carry what the actor provides. Events record what actually happened — including system-generated values (`registration-number`, timestamps) that did not exist before. The commands table and events table are therefore separate.

**Business rules sit on the commands, not the events.**
Events are facts that have already been validated. The business rules column is what a lawyer reviews to confirm the system enforces the law. Once an event is recorded, it is not checked again.
