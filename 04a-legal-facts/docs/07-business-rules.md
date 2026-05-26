# Business Rules

*Derived from the Companies Registration Act.*
*Status: In review.*

These rules are the authoritative specification for the Decider functions,
process managers, projectors, and schedulers in the registry system. Each
rule references the Act section from which it is derived. Rules marked `-`
are architectural constraints with no direct Act reference.


## Group 1 - Identity Verification

Every natural person who acts in the Registry system, or who appears in
company data as a director or Authorised Officer, must have a verified
identity flag or record. The Registry may receive that verification from a
trusted identity broker, a trusted identity provider, or a separate Registry
verification service.

This project does not perform the identity-proofing ceremony itself. It
consumes verified identity claims and verified-person records as external
facts.

Enforced by: the identity broker, verified-person registry, and command
handlers before protected commands enter a Decider.

| Rule | Description | Act |
|------|-------------|-----|
| BR-IV-001 | A protected Registry user must authenticate through a trusted identity broker that supplies a verified natural-person identity claim accepted by the Registry | §2 |
| BR-IV-002 | The Registry shall maintain a verified identity flag or record for every identity-verified natural person known to the Registry | §2 |
| BR-IV-003 | A natural person may not create a draft, submit a registration application, or act as an Authorised Officer unless their verified identity is recorded or supplied by the trusted identity broker | §2 |
| BR-IV-004 | A person may not be named as a proposed director, recorded as a director, or added as a director unless their verified identity is recorded by the Registry | §2, §9, §21 |
| BR-IV-005 | Identity verification records do not expire in this contract. Revocation of a verification record is out of scope | §2 |
| BR-IV-006 | The identity-proofing ceremony itself is out of scope for this project; the Registry consumes trusted verification outcomes rather than collecting identity evidence directly | §2 |

**Parked identity workflow**

In a later project, the registration workflow may include a parallel branch
that contacts unverified proposed directors, asks them to complete identity
verification through a trusted third-party provider, and resumes examination
only after confirmation is received. That workflow is not implemented in this
contract.


## Group 2 - Draft Aggregate

Enforced by: `DraftDecider`

A draft is a private workspace created by an Applicant in preparation for
a registration application. It has no legal existence.

| Rule | Description | Act |
|------|-------------|-----|
| BR-DR-001 | A draft is active if it has not been submitted, cancelled, or expired | §4 |
| BR-DR-002 | An Applicant may not hold more than 5 active drafts at any time | §4 |
| BR-DR-003 | A draft expires after the configured expiry period if not submitted - the default is 30 days; the period is set by the Registrar by order and must never be hardcoded | §5 |
| BR-DR-004 | Draft expiry is triggered by a scheduled job that issues an `ExpireDraft` command through the Decider - business logic and the audit trail must never be bypassed | §5 |
| BR-DR-005 | A draft may be cancelled by the Applicant at any time while it is in the `Active` state | - |
| BR-DR-006 | A draft that has been submitted may not be updated, cancelled, or resubmitted | - |
| BR-DR-007 | A draft that has been cancelled may not be updated or submitted | - |
| BR-DR-008 | A draft that has been expired may not be updated, cancelled, or submitted | - |
| BR-DR-009 | All drafts are retained permanently regardless of their status | §6 |
| BR-DR-010 | A draft has no legal existence and confers no rights or obligations | §3 |
| BR-DR-011 | An Applicant may only cancel their own drafts | - |
| BR-DR-012 | The Registry shall notify an Applicant when their draft is approaching its expiry date - the warning period is set by the Registrar by order and must never be hardcoded | §5 |
| BR-DR-013 | A cancelled or expired draft may not be reinstated - the Applicant must create a new draft | §4, §6 |
| BR-DR-014 | A draft may only be created by an identity-verified Applicant | §2, §3 |

**State machine:**

```edn
{:transitions
 {[:active :submit-draft]  :submitted  ;; ordered flow: see Submission ordered flow below
  [:active :cancel-draft]  :cancelled  ;; BR-DR-011 - Applicant only
  [:active :expire-draft]  :expired}   ;; BR-DR-004 - scheduler only, through Decider
 :terminal-states #{:submitted :cancelled :expired}} ;; BR-DR-013 - terminal states are irreversible
```

All states except `:active` are terminal. Terminal states are irreversible
(BR-DR-013).

**Submission ordered flow:**

```
1. Validate BR-SB-001 through BR-SB-006 - reject if any fail; no fee taken
2. Present fee amount and non-refundable notice to Applicant (BR-FE-005, BR-FE-008)
3. Take payment (BR-FE-001)
4. Create registration application
```


## Group 3 - Filing Fee

The filing fee is a precondition of submission. It is enforced in the
submission flow after validation passes. It has no aggregate of its own.

| Rule | Description | Act |
|------|-------------|-----|
| BR-FE-001 | A filing fee must be paid before a registration application is created | §12A |
| BR-FE-002 | The filing fee amount is set by the Registrar by order and must never be hardcoded | §12A |
| BR-FE-003 | The filing fee is non-refundable once the registration application has been formally created, regardless of the subsequent outcome of the application | §12A |
| BR-FE-004 | If submission is retried with the same command ID, no second payment shall be taken | - |
| BR-FE-005 | The Applicant must be clearly informed of the fee amount and its non-refundable nature before payment is taken | §12A |
| BR-FE-006 | If the filing fee is successfully deducted but the registration application is not created due to a system failure, the fee shall be refunded - this is the sole exception to BR-FE-003 | §12A |
| BR-FE-007 | The Registry shall issue a payment receipt to the Applicant upon successful payment, stating the amount paid, the payment date, and the draft ID | §12A |
| BR-FE-008 | Submission validation rules BR-SB-001 through BR-SB-006 must pass before the filing fee is presented - payment is never requested for a draft that fails validation | §12A |


## Group 4 - Submission Validation

Enforced by: `DraftDecider` (on the `SubmitDraft` command, before fee payment)

These rules are checked at submission. A draft failing any rule is rejected
and remains `Active`. No fee is taken and no registration application is
created.

| Rule | Description | Act |
|------|-------------|-----|
| BR-SB-001 | The draft must state a proposed company name | §7, §8 |
| BR-SB-002 | The draft must name at least two proposed directors | §8 |
| BR-SB-003 | Each proposed director must be a distinct person as determined by their Registry verification record ID - the same ID may not appear more than once | §8, §9 |
| BR-SB-004 | The draft must state a registered office address | §8 |
| BR-SB-005 | Every proposed director must be a natural person | §9 |
| BR-SB-006 | Every proposed director must have a verified-person record accepted by the Registry before the draft is submitted | §9 |

*Note: Company name uniqueness (BR-AP-007, BR-AP-010) and address validity
(BR-AP-009) are not checked at submission - they are the Registrar's legal
responsibility at approval under §14.*


## Group 5 - Requisitions

Enforced by: `RegistrationApplicationDecider`

A requisition is a formal written request raised by an assigned Examiner
during examination, requiring the Applicant to provide clarification or
additional information.

| Rule | Description | Act |
|------|-------------|-----|
| BR-RQ-001 | Only the Examiner assigned to an application may raise a requisition against it | §13 |
| BR-RQ-002 | A requisition may only be raised when the application is in the `UnderExamination` or `AwaitingRequisitionResponse` state | §13 |
| BR-RQ-003 | Every requisition must state the question or information required and the deadline by which the Applicant must respond | §13 |
| BR-RQ-004 | Raising a requisition when the application is in `UnderExamination` transitions the application to `AwaitingRequisitionResponse` | §13 |
| BR-RQ-005 | The Applicant is notified upon each requisition being raised | §13 |
| BR-RQ-006 | A requisition response must be submitted before the stated deadline | §13 |
| BR-RQ-007 | When all open requisitions have received responses, the application transitions back to `UnderExamination` and the assigned Examiner is notified | §13 |
| BR-RQ-008 | Every requisition and its response are permanently recorded on the application | §20 |
| BR-RQ-009 | When a requisition deadline passes without a response, the assigned Examiner is notified once automatically - the system takes no further automatic action | §13 |
| BR-RQ-010 | An application with open requisitions may not be referred to the Registrar for decision | §13 |
| BR-RQ-011 | An application in the `AwaitingRequisitionResponse` state may be withdrawn by the Applicant | §11 |
| BR-RQ-012 | The deadline for a requisition response must not exceed the maximum period set by the Registrar by order - this maximum must never be hardcoded | §13 |
| BR-RQ-013 | An Examiner may close a requisition without requiring a response where it was raised in error - the closure must state a reason and the Applicant must be notified; the closure is permanently recorded | §13 |
| BR-RQ-014 | After a requisition deadline passes without a response, only the assigned Examiner may record the next action; the action and reason must be permanently recorded | §13, §20 |
| BR-RQ-015 | A missed-deadline next action may extend the deadline and notify the Applicant, or proceed toward referral to the Registrar with the missed deadline recorded in the case history | §13 |

**Requisition state machine:**

A Requisition is an entity within the `RegistrationApplication` aggregate. Its
own lifecycle drives transitions in the application FSM: raising a requisition
moves the application to `:awaiting-requisition-response`; the last open
requisition reaching `:responded` or `:closed` moves it back to
`:under-examination`.

```edn
{:transitions
 {[:open    :applicant-responded]       :responded  ;; BR-RQ-006, BR-RQ-007 - triggers application back to :under-examination if last open
  [:open    :deadline-passed]           :overdue    ;; BR-RQ-009 - Examiner notified; system takes no further automatic action
  [:open    :close-in-error]            :closed     ;; BR-RQ-013 - Examiner closes; reason recorded; Applicant notified
  [:overdue :extend-deadline]           :open       ;; BR-RQ-015 - Examiner extends; new deadline notified to Applicant
  [:overdue :proceed-without-response]  :closed}    ;; BR-RQ-015 - Examiner proceeds; missed deadline recorded in case history
 :terminal-states #{:responded :closed}}
```

States `:responded` and `:closed` are terminal. The `:overdue` to `:open`
transition is a legal cycle - a deadline may be extended more than once.
Only the assigned Examiner may drive transitions from `:overdue` (BR-RQ-014).


## Group 6 - Registration Application Aggregate

Enforced by: `RegistrationApplicationDecider`

A registration application is a formal lodgement created when an Applicant
successfully submits a completed draft and pays the filing fee. It is a
legal act.

| Rule | Description | Act |
|------|-------------|-----|
| BR-RA-001 | A registration application is created by a process manager reacting to a `DraftSubmitted` event - it is never created directly by a user command | - |
| BR-RA-002 | A registration application permanently records the ID of the draft that created it | - |
| BR-RA-003 | An Applicant may withdraw their registration application at any time before the Registrar makes a decision - withdrawal is permitted in the `Submitted`, `UnderExamination`, `AwaitingRequisitionResponse`, and `ReadyForDecision` states | §11 |
| BR-RA-004 | An Applicant may only withdraw their own registration application | - |
| BR-RA-005 | All registration applications are retained permanently regardless of their status | §12 |
| BR-RA-006 | Only an Examiner may begin examination of an application | - |
| BR-RA-007 | Once examination has begun, the application is assigned to that Examiner and is no longer available to other Examiners | - |
| BR-RA-008 | Only the assigned Examiner may refer a completed application to the Registrar for decision | §13 |
| BR-RA-009 | An application may only be referred to the Registrar when it is in `UnderExamination` and has no open requisitions | §13 |
| BR-RA-010 | Only the Registrar may approve or reject an application, and only when it is in the `ReadyForDecision` state | §14, §16 |
| BR-RA-011 | A rejected application must state the reason for rejection | §16 |

**State machine:**

```edn
{:transitions
 {[:submitted                     :begin-examination]    :under-examination           ;; BR-RA-006
  [:under-examination              :raise-requisition]    :awaiting-requisition-response ;; BR-RQ-001, BR-RQ-002, BR-RQ-004
  [:awaiting-requisition-response  :requisition-answered] :under-examination           ;; BR-RQ-007
  [:under-examination              :refer-to-registrar]   :ready-for-decision          ;; BR-RA-008, BR-RA-009
  [:ready-for-decision             :approve-application]  :approved                    ;; BR-RA-010, BR-AP-001–BR-AP-010
  [:ready-for-decision             :reject-application]   :rejected                    ;; BR-RA-010, BR-RA-011
  [:submitted                     :withdraw-application] :withdrawn                   ;; BR-RA-003, BR-RA-004
  [:under-examination              :withdraw-application] :withdrawn                   ;; BR-RA-003
  [:awaiting-requisition-response  :withdraw-application] :withdrawn                   ;; BR-RQ-011
  [:ready-for-decision             :withdraw-application] :withdrawn}                  ;; BR-RA-003
 :terminal-states #{:approved :rejected :withdrawn}}
```

Non-terminal states: `:submitted`, `:under-examination`,
`:awaiting-requisition-response`, `:ready-for-decision`

Terminal states: `:approved`, `:rejected`, `:withdrawn`


## Group 7 - Approval Business Rules

Enforced by: `RegistrationApplicationDecider` (on the `ApproveApplication`
command)

These are the legally binding checks required by §14. Rules BR-AP-001
through BR-AP-006 repeat submission checks because the Registrar's approval
decision must be made against the authoritative record. Identity verification
records do not expire in this contract; revocation is out of scope.

| Rule | Description | Act |
|------|-------------|-----|
| BR-AP-001 | The application must state a proposed company name | §8, §14 |
| BR-AP-002 | The application must name at least two proposed directors | §8, §14 |
| BR-AP-003 | Each proposed director must be a distinct person as determined by their Registry verification record ID | §8, §9, §14 |
| BR-AP-004 | The application must state a registered office address | §8, §14 |
| BR-AP-005 | Every proposed director must be a natural person | §9, §14 |
| BR-AP-006 | Every proposed director must have a verified-person record accepted by the Registry | §9, §14 |
| BR-AP-007 | The proposed company name must not be identical to the current name of any company in the authoritative Register. This check must use write-side Register facts, not the Register read model. This approval check is non-reserving: it does not lock the name | §10, §14 |
| BR-AP-008 | The proposed company name must not fall within a prohibited category prescribed by the Registrar by order | §10, §14 |
| BR-AP-009 | The registered office address must be verified against the national address database, must not be a PO box, and must be usable as a registered office - if the address validation service is unavailable at approval, the system records the unavailability and the Registrar must make an explicit confirmatory decision before approval proceeds | §13, §14 |
| BR-AP-010 | The proposed company name must not be identical to any former name of a company that is within the protected period set by the Registrar by order. This check must use write-side Register facts, not the Register read model. This approval check is non-reserving: it does not lock the name | §10, §14 |


## Group 7A - Four-Eyes Rule (Separation of Examination and Decision)

The four-eyes rule is the requirement under §13A that no single natural person
may both examine and decide on the same registration application. It is an
institutional integrity control: the person who prepares the case must not be
the person who closes it. Role separation alone (Examiner role vs Registrar
role) is insufficient - the system must also prevent the same natural person
from acting in both capacities on the same application, even if they hold
both roles.

| Rule | Description | Act |
|------|-------------|-----|
| BR-4E-001 | The natural person who examines a registration application must not be the same natural person who approves or rejects it | §13A |
| BR-4E-002 | The system must record the `verified_person_id` of the Examiner when examination begins (BR-RA-006). At the point of approval or rejection, the system must reject the command if the `verified_person_id` of the acting Registrar matches the recorded Examiner `verified_person_id` of that application | §13A |
| BR-4E-003 | A decision to approve or reject an application where the acting Registrar is the same natural person as the Examiner is invalid and must be rejected with a permanent record of the attempted breach | §13A |
| BR-4E-004 | The four-eyes check is an ABAC rule applied after RBAC and before the state machine guard. A Registrar who passes RBAC but fails the four-eyes check receives a distinct rejection reason (`four-eyes-violation`) that is recorded in the audit log | §13A |


## Group 8 - Authorised Officers

An Authorised Officer is a person authorised to act on behalf of a
registered company for post-registration obligations. The initial
Authorised Officers are the directors named in the approved registration
application.

| Rule | Description | Act |
|------|-------------|-----|
| BR-AO-001 | Upon registration, each director named in the approved registration application is automatically recorded as an Authorised Officer of the company | §1, §15 |
| BR-AO-002 | An Authorised Officer must be an identity-verified natural person | §1, §2 |
| BR-AO-003 | Only an Authorised Officer of a company may submit director change notifications, address change notifications, annual returns, or dissolution applications on behalf of that company | §1, §21, §22, §24 |
| BR-AO-004 | The Registry shall maintain a current record of Authorised Officers per registered company | §1 |
| BR-AO-005 | Authorised Officer status is recorded as an event on the registered company's event stream | - |
| BR-AO-006 | When a director change notification removes a director, that person's Authorised Officer status is simultaneously revoked, unless they have been separately authorised in writing by the remaining directors | §1, §21 |
| BR-AO-007 | When a director change notification adds a new director, that person is automatically recorded as an Authorised Officer upon the change being recorded | §1, §21 |


## Group 9 - Registration and Registration Number

Enforced by: `RegisteredCompanyDecider` (on the `CreateRegisteredCompany`
command, issued by the approval process manager)

| Rule | Description | Act |
|------|-------------|-----|
| BR-RG-001 | A registered company is created by a process manager reacting to a `RegistrationApplicationApproved` event - it is never created directly by a user command | - |
| BR-RG-002 | The Registration Number is assigned at the moment of registration by the process manager | §15 |
| BR-RG-003 | The Registration Number is permanent and must never be reused or reassigned, even after strike-off or dissolution | §15, §23, §24 |
| BR-RG-004 | A company becomes a legal entity upon assignment of its Registration Number | §15 |
| BR-RG-005 | The registered company's event stream permanently records the ID of the registration application that created it | - |
| BR-RG-006 | `CreateRegisteredCompany` must enforce final company-name uniqueness in the authoritative Register before recording `RegisteredCompanyCreated`. A failure here prevents company creation, no Registration Number is assigned, and no registration notification is sent. Because name reservation is parked for future amendment, this is a known operational exception path rather than a solved workflow | §10, §10A, §15, §17 |


## Group 10 - Registered Company Aggregate

Enforced by: `RegisteredCompanyDecider`

| Rule | Description | Act |
|------|-------------|-----|
| BR-RC-001 | A registered company must, by its Authorised Officer, notify the Registry of any change to its directors within the period prescribed by the Registrar | §21 |
| BR-RC-002 | A registered company must, by its Authorised Officer, notify the Registry of any change to its registered office address within the period prescribed by the Registrar | §21 |
| BR-RC-003 | A registered company must, by its Authorised Officer, file an annual return in the form and within the period prescribed by the Registrar | §22 |
| BR-RC-004 | The Registrar may strike a company off the Register for failure to comply with its obligations under this Act | §23 |
| BR-RC-005 | A registered company may, by its Authorised Officer, apply to the Registrar to be dissolved voluntarily, provided it has no outstanding obligations | §24 |
| BR-RC-006 | For this micro-registry contract, outstanding obligations preventing dissolution are limited to: unfiled annual returns within the prescribed period, and unpaid fees owed to the Registry | §24 |
| BR-RC-007 | A struck-off company ceases to be a legal entity from the date of strike-off | §23 |
| BR-RC-008 | A dissolved company ceases to be a legal entity from the date of dissolution | §24 |
| BR-RC-009 | Strike-off and dissolution do not remove the company from the historical record of the event store | §23, §24 |
| BR-RC-010 | A director change may not be recorded unless the company will continue to have at least two current directors after the change | §21 |
| BR-RC-011 | A director change may not add a director unless that person is a distinct identity-verified natural person | §21 |
| BR-RC-012 | A registered office change may not be recorded unless the new address is plausible, valid, within the jurisdiction, and usable as an address for service of legal notices | §21 |
| BR-RC-013 | An annual return must identify the return period it covers and must be in the form prescribed by the Registrar | §22 |
| BR-RC-014 | Only one annual return may be accepted for the same company and return period | §22 |
| BR-RC-015 | The annual return form and filing period are set by the Registrar by order and must never be hardcoded | §22 |

**State machine:**

```edn
{:transitions
 {[:registered :strike-off] :struck-off  ;; BR-RC-004 - Registrar only
  [:registered :dissolve]   :dissolved}  ;; BR-RC-005, BR-RC-006 - Registrar only, following AO application
 :terminal-states #{:struck-off :dissolved}}
```

All states except `:registered` are terminal.

*Note: Director changes, address changes, annual returns, and AO changes are
recorded as events on the RegisteredCompany aggregate but do not change its
top-level state.*


## Group 11 - The Register

The Register is the event store - specifically the set of `RegisteredCompany`
event streams containing a `RegisteredCompanyCreated` event. It is the legal
source of truth. A company exists because that event exists. The event store
is the write model.

The **Register read model** - the `register` table in Postgres - is a
queryable projection of the Register, maintained by the Register projector.
It serves public inspection and search. It is derived, disposable, and
rebuildable from the event store. It is not the Register.

| Rule | Description | Act |
|------|-------------|-----|
| BR-RI-001 | The Register is the authoritative legal record of registered companies - it is the set of `RegisteredCompany` event streams in the event store | §17 |
| BR-RI-002 | A company exists as a legal entity if and only if a `RegisteredCompanyCreated` event exists in the event store for that company | §17 |
| BR-RI-003 | The Register shall be open to public inspection - this obligation is discharged through the Register read model | §19 |
| BR-RI-004 | No event in the Register's event streams may be altered or deleted - the Register is an append-only legal record | §20 |
| BR-RI-005 | The Register read model must never be written to directly - only the Register projector may write to it, and only in response to: `RegisteredCompanyCreated`, `DirectorChanged`, `RegisteredOfficeChanged`, `CompanyStruckOff`, `CompanyDissolved` | §18, §20, §21 |
| BR-RI-006 | Company name uniqueness must be enforced in the authoritative write-side Register path before a `RegisteredCompanyCreated` event is recorded. A database unique constraint on the Register read model may exist as defensive projection protection, but the read model is not the legal concurrency guard | §10, §17 |
| BR-RI-007 | The Register read model must reflect both the current state of each company (current directors, current address, current status) and retain the particulars as they stood at the date of registration, including all former names with the dates they were in use | §18 |
| BR-RI-008 | For basic public director-name search, "current directors" means the latest recorded directors in the company's event stream, even where the company's current status is `StruckOff` or `Dissolved`. Former directors removed before that latest recorded state are not returned by basic search | §18, §19 |


## Group 14 - Data Classification

These rules define the classification taxonomy, assign classifications to data
fields and document types, and govern logging behaviour. They are derived from
the Act's public inspection provisions (§20–§22) and from data protection
obligations. Enforcement is via FLS/RLS (ADR-0020, ADR-0025) and log masking
at the point of emission.

### Classification Taxonomy

| Class | Meaning | Default Access | Application Log | Audit Log |
|-------|---------|----------------|-----------------|-----------|
| **Public** | Required to be publicly inspectable under the Act | Open (all authenticated and unauthenticated callers) | Plaintext permitted | Full fidelity |
| **Restricted** | Internal Registry operational data | Registry staff (`examiner`, `registrar`, `admin`) | Replaced with `[RESTRICTED]` | Full fidelity |
| **Confidential** | Sensitive personal data or identity documents | Named roles only (see table below) | Replaced with `[CONFIDENTIAL]` | Full fidelity |
| **Sealed** | Court order or regulatory hold | Authorised officers and `admin` only | Replaced with `[SEALED]` | Full fidelity |

### Data Field Classification

| Field | Classification | Permitted Roles | Notes |
|-------|---------------|----------------|-------|
| Company name | Public | All | Publicly inspectable under §20 |
| Registration number | Public | All | Publicly inspectable under §20 |
| Registered office address | Public | All | Publicly inspectable under §20; see BR-DC-005 for home address exception |
| Company status | Public | All | Publicly inspectable under §20 |
| Date of registration | Public | All | Publicly inspectable under §20 |
| Director full name | Public | All | Publicly inspectable under §20 |
| Director date of birth (month/year only) | Public | All | Only month and year; full DOB is Confidential |
| Director full date of birth | Confidential | `examiner`, `registrar`, `admin` | Full DOB not part of public record |
| Director home address | Confidential | `examiner`, `registrar`, `admin` | Home address not publicly disclosed; see BR-DC-005 |
| Applicant email address | Restricted | `examiner`, `registrar`, `admin` | Operational contact; not part of public Register |
| Payment reference number | Confidential | `registrar`, `admin` | Financial reference; not part of public Record |
| Identity verification result | Confidential | `examiner`, `registrar`, `admin` | Verification outcome from Identity Broker |
| Command ID / causation ID / correlation ID | Restricted | `admin` | Internal operational metadata |
| Examiner notes and annotations | Restricted | `examiner`, `registrar`, `admin` | Internal examination working notes |
| Rejection reason (internal full text) | Restricted | `examiner`, `registrar`, `admin` | Summary decision is Public; full internal reasoning is Restricted |
| Process manager state | Restricted | `admin` | Internal workflow state |
| Outbox relay status | Restricted | `admin` | Internal operational state |

### Document Type Classification

Document classification may change at lifecycle transitions (see BR-DC-007).

| Document Type | Classification at Upload | Classification After Registration | Notes |
|---------------|--------------------------|-----------------------------------|-------|
| Draft content (form fields) | Restricted | N/A (draft never Public) | Draft has no legal existence; not part of public Register |
| Articles of association / constitution | Restricted | Public | Becomes a public filing upon registration |
| Certificate of incorporation | N/A (generated) | Public | Generated by Registry; publicly inspectable |
| Director consent form | Confidential | Confidential | Contains personal signature; home address may be present |
| Passport / national ID (identity proof) | Confidential | Confidential | Never published; Registry internal only |
| Proof of address (identity verification) | Confidential | Confidential | Never published; Registry internal only |
| Examiner report | Restricted | Restricted | Internal examination record; not part of public Register |
| Requisition letter | Restricted | Restricted | Internal examination correspondence |
| Annual return filing | Restricted | Public | Becomes a public filing upon acceptance |

### Business Rules

| Rule | Description | Act |
|------|-------------|-----|
| BR-DC-001 | Every data field stored or processed by the Registry must carry an explicit classification label as defined in the taxonomy above | §20 |
| BR-DC-002 | Every document type uploaded to or generated by the Registry must carry an explicit classification label | §20 |
| BR-DC-003 | A field classified Public may be returned in public API responses without authentication | §21 |
| BR-DC-004 | A field classified Restricted must not be returned in any response to a caller whose claims do not include an authorised Registry role | §20 |
| BR-DC-005 | Where a director's home address is used as the registered office address, it retains Confidential classification and must not appear on the public Register; a substitute service address must be recorded for public disclosure | §9 |
| BR-DC-006 | A document classified Confidential must not be accessible via any endpoint available to applicants or the public | §20 |
| BR-DC-007 | Document classification may be promoted from Restricted to Public only at a defined lifecycle transition (e.g. registration approval). Classification may never be demoted from a higher to a lower level | - |
| BR-DC-008 | Any field classified Restricted, Confidential, or Sealed must be replaced with the classification marker `[RESTRICTED]`, `[CONFIDENTIAL]`, or `[SEALED]` respectively at the point of emission into application logs | - |
| BR-DC-009 | Application logs must not contain plaintext values for: director home addresses, dates of birth, payment references, identity verification results, or any uploaded document content | - |
| BR-DC-010 | The audit log records full-fidelity values for all fields, regardless of classification. No masking is applied to the audit log | §20 |
| BR-DC-011 | Access to the audit log is restricted to the `admin` role and to external auditors via a dedicated read-only database connection using the `audit_reader` database role. No application API endpoint may return audit log rows | §20 |
| BR-DC-012 | Access to application logs is restricted to operations and infrastructure personnel. Application logs must not be accessible to domain staff (examiners, registrars, applicants) through any Registry user interface or API | - |
| BR-DC-013 | A Sealed record may only be unsealed by explicit order from the Registry authority or by court order, recorded as a new event in the event store | - |

## Parked Rules - Name Reservation

Name reservation and name locking are a known gap in this contract. The likely
future model is:

```
Draft name check       → non-binding availability feedback
Successful submission  → lock proposed name
Terminal application   → release lock unless registration succeeded
Registration           → consume lock and create registered company
```

That workflow has legal consequences and requires a fuller amendment to the
Act. It is deliberately not implemented in the current rules.

| Rule | Status | Description | Act |
|------|--------|-------------|-----|
| BR-NR-P001 | Parked | A draft may provide non-binding name-availability feedback to the Applicant before submission | §10A |
| BR-NR-P002 | Parked | Successful submission may lock the proposed company name until the registration application reaches a terminal state | §10A |
| BR-NR-P003 | Parked | A name lock should prevent another draft or application from locking the same name before registration is completed or the lock is released | §10A |
| BR-NR-P004 | Parked | A name lock should be consumed when `RegisteredCompanyCreated` is recorded, and released if the application is withdrawn or rejected | §10A |


## Parked Rules - Fuller Outstanding Obligations

The Act uses the broader phrase "outstanding obligations under this Act".
For this micro-registry contract, active dissolution blocking is deliberately
limited to unfiled annual returns and unpaid Registry fees (BR-RC-006). A real
registry would need a fuller obligations model before voluntary dissolution.

| Rule | Status | Description | Act |
|------|--------|-------------|-----|
| BR-OB-P001 | Parked | Overdue director-change notifications should block voluntary dissolution until resolved | §21, §24 |
| BR-OB-P002 | Parked | Overdue registered-office-change notifications should block voluntary dissolution until resolved | §21, §24 |
| BR-OB-P003 | Parked | Unresolved compliance action or pending strike-off proceedings should block voluntary dissolution until closed or explicitly overridden by the Registrar | §23, §24 |
| BR-OB-P004 | Parked | Outstanding penalties, late-filing charges, or other statutory debts should block voluntary dissolution until paid or remitted | §22, §24 |


## Group 12 - Command Idempotency

These rules are cross-cutting. They apply to commands handled by every
Decider and process manager.

| Rule | Description | Act |
|------|-------------|-----|
| BR-ID-001 | Every command must carry a stable command ID | - |
| BR-ID-002 | Command idempotency is enforced by a command ledger keyed by command ID, not by a global uniqueness constraint on event causation ID | - |
| BR-ID-003 | If a command with the same command ID is received again, the system must return the previously recorded command outcome and must not repeat side effects such as payment, event appends, notifications, or registered-company creation | - |
| BR-ID-004 | Event causation ID records the command that caused an event. Multiple events may legitimately share the same causation ID when they are caused by the same command | - |
| BR-ID-005 | Correlation ID records the original request that triggered a chain of commands, events, and process-manager actions | - |


## Group 13 - Audit and Retention

These rules are cross-cutting. They apply to every aggregate, every event,
and every action in the system.

| Rule | Description | Act |
|------|-------------|-----|
| BR-AU-001 | All drafts are retained permanently in the event store, regardless of status | §6 |
| BR-AU-002 | All registration applications are retained permanently in the event store, regardless of status | §12 |
| BR-AU-003 | All registered company records are retained permanently in the event store, including those of struck-off and dissolved companies | §23, §24 |
| BR-AU-004 | The Registry shall maintain a complete and tamper-evident record of all events across all aggregates | §20 |
| BR-AU-005 | Every event must record the identity of the person who caused it | §20 |
| BR-AU-006 | Every event must record the date and time at which it occurred | §20 |
| BR-AU-007 | Every event must record the command that caused it (causation ID) and the original request that triggered the chain (correlation ID) | - |
| BR-AU-008 | No event may ever be altered or deleted from the event store | §20 |


## Group 15 - Workflow FSMs (Process Manager State Machines)

Process managers coordinate workflows that span multiple aggregate boundaries
(ADR-0006). Each process manager persists its own state as an explicit FSM so
it can recover from crashes and resume from the correct step (ADR-0024).

**Important distinction from aggregate FSMs:**
Aggregate FSM transitions are triggered by *commands* (`[from-state command]`).
Process manager FSM transitions are triggered by incoming *events*
(`[from-state event-received]`). The command issued on each transition is
documented in the inline comment.

### Submission Process Manager

**Trigger:** `DraftSubmitted` event.
**Responsibility:** Orchestrate fee payment and address validation; issue
`CreateRegistrationApplication` on success; record failure on any rejection.

```edn
{:transitions
 {[:initiated                    :draft-submitted]                    :awaiting-payment-outcome
  ;; ^ issues InitiatePayment command

  [:awaiting-payment-outcome      :payment-confirmed]                  :awaiting-address-validation
  ;; ^ issues ValidateRegisteredOffice command

  [:awaiting-payment-outcome      :payment-failed]                     :failed
  [:awaiting-payment-outcome      :payment-timed-out]                  :failed

  [:awaiting-address-validation   :address-validated]                  :creating-application
  ;; ^ issues CreateRegistrationApplication command

  [:awaiting-address-validation   :address-invalid]                    :failed

  [:awaiting-address-validation   :validation-service-unavailable]     :creating-application
  ;; ^ issues CreateRegistrationApplication with :address-validation-unavailable flag
  ;; BR-AP-009: Registrar must make an explicit confirmatory decision at approval time

  [:creating-application          :application-created]                :complete}

 :terminal-states #{:complete :failed}}
```

Terminal states: `:complete`, `:failed`.

If the PM reaches `:failed`, the draft remains in `:submitted` state. No
registration application is created. A human support process is required to
notify the Applicant and advise on next steps (parked scope).

### Approval Process Manager

**Trigger:** `RegistrationApplicationApproved` event.
**Responsibility:** Issue `CreateRegisteredCompany` (assigning the Registration
Number); wait for confirmation; trigger registration notification.

```edn
{:transitions
 {[:initiated                 :application-approved]           :awaiting-company-created
  ;; ^ issues CreateRegisteredCompany command with generated Registration Number (BR-RG-002)

  [:awaiting-company-created  :company-created]                :notifying-applicant
  ;; ^ issues SendRegistrationNotification command

  [:awaiting-company-created  :creation-failed]                :failed
  ;; ^ must never occur under normal conditions; recorded for human resolution

  [:notifying-applicant       :notification-sent]              :complete
  [:notifying-applicant       :notification-failed]            :complete}
  ;; ^ notification failure does NOT invalidate legal registration (BR-RG-001)
  ;; the company is legally registered on RegisteredCompanyCreated; notification is best-effort

 :terminal-states #{:complete :failed}}
```

Terminal states: `:complete`, `:failed`.

`:failed` on this PM represents a serious infrastructure fault (company
creation command failed despite approved application). The legal registration
fact has not yet been recorded. This must trigger an immediate operational
alert and human review — it is not a recoverable business scenario.
