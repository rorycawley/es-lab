# Ubiquitous Language

This document defines the terms used by the Registry, the domain model, tests, API, and documentation. If code or documentation needs a term that is not listed here, add it here first or explain why it is local implementation detail.

---

## People and roles

| Term | Meaning | Notes |
|---|---|---|
| Applicant | Person who starts and submits a registration application | In this project, `X-User-Id` identifies the applicant but does not prove they are a natural person |
| Proposed director | Natural person named on a registration application as a proposed company director | Must have a verified identity before submission; identity verification proves natural-person status for this project |
| Director | Legally accepted company director after registration | Reserved for the post-registration company role |
| Examiner | Registry user who begins examination of a submitted application | Examination is a process state, not a legal decision |
| Registrar | Registry user who makes the approval or rejection decision | Only the Registrar may approve or reject in the domain language |
| Registry | The public authority operating the registration process | The Registry examines applications and maintains the Register |

---

## Legal records

| Term | Meaning | Notes |
|---|---|---|
| Register | Authoritative legal record of registered companies | In this implementation, the Register is the current `company-registered` facts in the event store |
| Register projection | Query model rebuilt from Register events | Useful for searching and reporting; not the legal source of truth |
| Company record | The current legal company fact in the Register | Created once, from `company-registered`; no update path in this project |
| Registration number | Permanent unique identifier assigned when a company is registered | Must not be reused |
| Registered company | Company that legally exists in the Register | Final lifecycle state for this project |
| Registered office address | Address stated on the application and recorded for the company | Required by CRA §2 |
| Valid address | Address that is plausible and accepted as usable by the Registry | Checked during examination before approval |

---

## Application lifecycle

| Term | Meaning | Entered by |
|---|---|---|
| Draft | Application shell exists but has not been formally submitted | `registration-application-created` |
| Submitted | Applicant has supplied required details and lodged the application | `registration-application-submitted` |
| Under examination | Registry examination has begun | `examination-started` |
| Withdrawn | Applicant closed the application before a decision | `registration-application-withdrawn` |
| Rejected | Registrar decided the application does not comply with the Act | `registration-application-rejected` |
| Registered | Company legally exists in the Register | `company-registered` |

---

## Commands

Commands are requests. A command can be rejected and does not become part of the legal history unless it emits an event.

| Command | Meaning |
|---|---|
| `create-registration-application` | Create a draft application shell for an applicant |
| `submit-registration-application` | Lodge a draft application with company details |
| `begin-examination` | Move a submitted application into Registry examination |
| `withdraw-registration-application` | Close an application before a decision |
| `approve-registration-application` | Record the Registrar's approval decision and register the company atomically |
| `reject-registration-application` | Record the Registrar's rejection decision and reason |

---

## Events

Events are facts. Once recorded, an event is not edited or removed.

| Event | Meaning |
|---|---|
| `registration-application-created` | A draft application was created |
| `registration-application-submitted` | An application was formally lodged |
| `examination-started` | Registry examination started |
| `registration-application-withdrawn` | The applicant withdrew before decision |
| `registration-application-approved` | The Registrar approved the application |
| `registration-application-rejected` | The Registrar rejected the application |
| `company-registered` | The company was entered on the Register and legally exists |

---

## Rules and facts

| Term | Meaning |
|---|---|
| Business rule | Domain check that can reject a command after the state machine allows it |
| Legal fact | Fact with legal effect once recorded, such as `company-registered` |
| Application decision | Registrar approval or rejection of an application |
| Name reservation | Temporary claim on a proposed company name before registration; parked for a later project |
| Identity verification | Registry confirmation that a person is who they claim to be and is a natural person |
| Natural person | Human person, not an organisation or automated actor |

---

## Naming rules

- Use `registration application` for the process object.
- Use `registered company` for the legal result.
- Use `Register` for the authoritative legal record in the event store.
- Use `Register projection` for any derived read model or query cache.
- Use `Registry` for the public authority running the process.
- Use lower kebab-case past-tense event names: `registration-application-submitted`, `company-registered`.
- Use verb-first command names: `submit-registration-application`, `approve-registration-application`.
- Use state names exactly as listed in this document: Draft, Submitted, Under examination, Withdrawn, Rejected, Registered.

---

## Future vocabulary

These terms are useful for discussing later projects, but are out of scope for this registration exercise.

| Term | Kind | Meaning |
|---|---|---|
| `change-director` | Future command | Request a director change after registration |
| `director-changed` | Future event | A director change was accepted |
| `change-registered-office` | Future command | Request a registered-office change after registration |
| `registered-office-changed` | Future event | A registered-office change was accepted |
| `file-annual-return` | Future command | File an annual return after registration |
| `annual-return-filed` | Future event | An annual return was filed |
| `strike-off-company` | Future command | Strike a registered company off the Register |
| `company-struck-off` | Future event | A company was struck off the Register |
