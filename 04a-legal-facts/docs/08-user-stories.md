# User Stories

*Derived from the Companies Registration Act and the Business Rules.*
*Status: In review.*

Story IDs are stable references. All business rules (BR-xx-nnn) and Act
sections (§n) referenced here are defined in their respective documents.

Each story carries traceability metadata in its References block:

```
**References:** §X, BR-XX-nnn
**Characteristics:** <characteristic names from 06-architectural-characteristics.md>
**Fitness functions:** <FF-nnn IDs>
**ADRs:** <ADR-nnnn IDs>
```

Acceptance criterion IDs (`AC-XX-nnn-nnn`) are used as test names directly.
See `APPROACH.md` for the full traceability model.

*Note: Traceability metadata is to be completed for all stories. US-RE-002 below
is the canonical example of the completed format.*

**Actors:**

| ID | Actor | Description |
|----|-------|-------------|
| AP | Applicant | An identity-verified natural person who creates drafts and submits registration applications |
| AO | Authorised Officer | An identity-verified director or authorised representative acting on behalf of a registered company for post-registration obligations (defined in §1) |
| EX | Examiner | A Registry officer who examines registration applications |
| RE | Registrar | The Registry officer who makes decisions on applications and maintains the Register |
| PB | Public | Any person inspecting the Register — no authentication required |
| SY | System | Automated Registry processes (schedulers, process managers) |

*Note: Every natural person who acts in the Registry system or appears in
company data must have a verified identity flag or record. The initial
Authorised Officers upon registration are the directors named in the approved
application (BR-AO-001).*

---

## Part 1 — Authentication

### US-AP-016 — Authenticate with the Registry

*As any authenticated user (Applicant, Authorised Officer, Examiner, or
Registrar), I want to log in using my identity account so that I can access
the Registry's protected features. Public users do not authenticate.*

**References:** §2

*Note: Authentication uses an OIDC identity broker. The Registry only accepts
protected-user sessions where the trusted broker supplies a verified
natural-person identity claim. The identity-proofing ceremony is performed by
trusted identity providers or a future Registry verification service, not by
this project.*

| ID | Acceptance Criterion |
|----|----------------------|
| AC-AP-016-001 | Given I have a valid identity account with the identity broker, when I initiate login, then I am redirected to the identity broker to authenticate |
| AC-AP-016-002 | Given I have successfully authenticated with the identity broker, when I am redirected back to the Registry, then I am logged in and can access my account |
| AC-AP-016-003 | Given I have failed authentication with the identity broker, then I am returned to the login page with an appropriate message |
| AC-AP-016-004 | Given I am logged in, when I explicitly log out, then my session is immediately terminated and I must authenticate again to access any protected feature |
| AC-AP-016-005 | Given my session has been inactive for longer than the configured session timeout, then I must re-authenticate before accessing any protected feature |
| AC-AP-016-006 | Given I am unauthenticated, when I attempt to access any protected feature, then I am redirected to the login page |
| AC-AP-016-007 | Given the identity broker does not supply a verified natural-person identity claim, when I attempt to access a protected Registry feature, then access is rejected |

---

## Part 2 — Identity Verification

### US-AP-001 — Hold a verified natural-person identity

*As a natural person, I want the Registry to recognise my verified identity so
that I may act as an Applicant, be named as a proposed director, be recorded as
a director, or act as an Authorised Officer where the other rules allow it.*

*Note: This project does not perform the identity-proofing ceremony. It
consumes trusted verified identity claims from the identity broker and
verified-person records from external verification services. A future workflow
may contact unverified proposed directors and wait for third-party
verification before approval can proceed.*

**References:** §2, BR-IV-001 to BR-IV-006

| ID | Acceptance Criterion |
|----|----------------------|
| AC-AP-001-001 | Given I authenticate through a trusted identity provider and the identity broker supplies a verified natural-person identity claim, then the Registry records or updates my verified-person identity reference for this session |
| AC-AP-001-002 | Given my verified-person identity is recorded, then I may create drafts, submit registration applications, be named as a proposed director, be recorded as a director, or act as an Authorised Officer where the other rules allow it |
| AC-AP-001-003 | Given my verified-person identity is not recorded or supplied by the trusted identity broker, when I attempt to create a draft, submit an application, or act as an Authorised Officer, then the request is rejected |
| AC-AP-001-004 | Given a proposed director does not have a verified-person record, when a draft naming that person is submitted, then the submission is rejected with a reason stating that the proposed director is not identity-verified |
| AC-AP-001-005 | Given a new director does not have a verified-person record, when a director change attempts to add that person, then the director change is rejected |
| AC-AP-001-006 | Given a verified-person record exists, then it does not expire in this contract; revocation and re-verification are out of scope |

---

## Part 3 — Drafts

### US-AP-002 — Create a draft

*As an Applicant, I want to create a new draft so that I have a private
workspace in which to prepare my registration application.*

**References:** §2, §3, §4, BR-IV-003, BR-DR-001, BR-DR-002, BR-DR-003,
BR-DR-010, BR-DR-014

| ID | Acceptance Criterion |
|----|----------------------|
| AC-AP-002-001 | Given I am an authenticated Applicant with fewer than 5 active drafts, when I request a new draft, then a new draft is created in the `Active` state and I receive its unique draft ID |
| AC-AP-002-002 | Given I am an authenticated Applicant with 5 active drafts, when I request a new draft, then the request is rejected and I am told I have reached the maximum number of active drafts |
| AC-AP-002-003 | Given a draft has been created, then it has no legal existence and is not visible to Examiners or the Registrar |
| AC-AP-002-004 | Given a draft has been created, then it is assigned a creation timestamp from which the 30-day expiry is calculated |
| AC-AP-002-005 | Given a draft is created with minimal information, then it is accepted without any completeness validation |
| AC-AP-002-006 | Given my verified natural-person identity is not recorded or supplied by the trusted identity broker, when I request a new draft, then the request is rejected |

---

### US-AP-003 — Update a draft

*As an Applicant, I want to update the contents of my draft so that I can
build up the required information across multiple sessions before submitting.*

**References:** §3, BR-DR-005, BR-DR-006, BR-DR-007, BR-DR-008

| ID | Acceptance Criterion |
|----|----------------------|
| AC-AP-003-001 | Given my draft is in the `Active` state, when I update its contents, then the changes are saved and the draft remains in the `Active` state |
| AC-AP-003-002 | Given my draft is in the `Active` state, when I update it, then I can update the proposed company name, proposed directors, and registered office address individually or together |
| AC-AP-003-003 | Given my draft is in the `Submitted` state, when I attempt to update it, then the request is rejected |
| AC-AP-003-004 | Given my draft is in the `Cancelled` state, when I attempt to update it, then the request is rejected |
| AC-AP-003-005 | Given my draft is in the `Expired` state, when I attempt to update it, then the request is rejected |
| AC-AP-003-006 | Given I attempt to update another applicant's draft, then the request is rejected regardless of the draft's state |

---

### US-AP-004 — Cancel a draft

*As an Applicant, I want to cancel a draft I no longer intend to submit so
that it is removed from my active drafts.*

**References:** §4, §6, BR-DR-001, BR-DR-005, BR-DR-007, BR-DR-009,
BR-DR-011, BR-DR-013

| ID | Acceptance Criterion |
|----|----------------------|
| AC-AP-004-001 | Given my draft is in the `Active` state, when I cancel it, then the draft transitions to the `Cancelled` state |
| AC-AP-004-002 | Given my draft has been cancelled, then it no longer counts towards my limit of 5 active drafts |
| AC-AP-004-003 | Given my draft has been cancelled, then it is retained permanently in the Registry's records |
| AC-AP-004-004 | Given my draft has been cancelled, then it is no longer visible in my list of active drafts |
| AC-AP-004-005 | Given my draft is in the `Submitted` state, when I attempt to cancel it, then the request is rejected |
| AC-AP-004-006 | Given my draft is in the `Expired` state, when I attempt to cancel it, then the request is rejected |
| AC-AP-004-007 | Given I attempt to cancel another applicant's draft, then the request is rejected |
| AC-AP-004-008 | Given my draft has been cancelled, then it may not be reinstated — I must create a new draft if I wish to proceed (BR-DR-013) |

---

### US-AP-005 — View own active drafts

*As an Applicant, I want to see a list of my active drafts so that I can
manage my work in progress.*

**References:** §3, §4, BR-DR-001

| ID | Acceptance Criterion |
|----|----------------------|
| AC-AP-005-001 | Given I am authenticated, when I request my drafts, then I see only drafts I created that are in the `Active` state |
| AC-AP-005-002 | Given I have no active drafts, when I request my drafts, then I receive an empty list |
| AC-AP-005-003 | Given I have active drafts, then each entry shows the draft ID, creation date, expiry date, and a summary of the proposed company name if one has been entered |
| AC-AP-005-004 | Given another applicant's drafts exist, then they do not appear in my list |

---

### US-AP-006 — View a specific draft

*As an Applicant, I want to view the full details of one of my drafts so
that I can review its contents before submitting.*

**References:** §3, BR-DR-010

| ID | Acceptance Criterion |
|----|----------------------|
| AC-AP-006-001 | Given my draft exists in any state, when I request it by its ID, then I receive its full contents including state, creation date, and expiry date if applicable |
| AC-AP-006-002 | Given I request another applicant's draft by ID, then the request is rejected |
| AC-AP-006-003 | Given my draft does not exist, when I request it by ID, then I receive a not-found response |

---

## Part 4 — Submission and Payment

### US-AP-007 — Submit a draft as a registration application

*As an Applicant, I want to submit my completed draft so that it becomes a
formal registration application lodged with the Registry.*

**References:** §7, §8, §9, §12A, BR-SB-001 to BR-SB-006, BR-FE-001,
BR-FE-008, BR-DR-006, BR-RA-001, BR-RA-002, BR-ID-001 to BR-ID-003

| ID | Acceptance Criterion |
|----|----------------------|
| AC-AP-007-001 | Given my draft is in the `Active` state, satisfies all submission rules, and the filing fee is paid successfully, when I submit it, then the draft transitions to the `Submitted` state |
| AC-AP-007-002 | Given submission validation passes and the filing fee is paid successfully, then a new registration application is created with its own unique ID, distinct from the draft ID |
| AC-AP-007-003 | Given a draft is successfully submitted, then the registration application permanently records the ID of the draft that created it |
| AC-AP-007-004 | Given a draft is successfully submitted, then the full contents of the draft are copied into the registration application |
| AC-AP-007-005 | Given my draft does not state a proposed company name, when I submit it, then the submission is rejected with reason citing BR-SB-001 |
| AC-AP-007-006 | Given my draft names fewer than two proposed directors, when I submit it, then the submission is rejected with reason citing BR-SB-002 |
| AC-AP-007-007 | Given my draft names the same person as a director more than once, when I submit it, then the submission is rejected with reason citing BR-SB-003 |
| AC-AP-007-008 | Given my draft does not state a registered office address, when I submit it, then the submission is rejected with reason citing BR-SB-004 |
| AC-AP-007-009 | Given my draft names a proposed director who is not a natural person, when I submit it, then the submission is rejected with reason citing BR-SB-005 |
| AC-AP-007-010 | Given my draft names a proposed director who does not have a verified-person record accepted by the Registry, when I submit it, then the submission is rejected with reason citing BR-SB-006 |
| AC-AP-007-011 | Given a submission is rejected for any reason, then the draft remains in the `Active` state and no registration application is created |
| AC-AP-007-012 | Given my draft is in the `Submitted`, `Cancelled`, or `Expired` state, when I attempt to submit it, then the request is rejected |
| AC-AP-007-013 | Given I submit the same draft twice using the same command ID, then the second request returns the previously recorded command outcome and no second registration application is created |
| AC-AP-007-014 | Given all submission validation rules pass, when I attempt to submit without paying the statutory filing fee, then the submission is rejected with a reason stating that payment is required |
| AC-AP-007-015 | Given the statutory filing fee is changed by the Registrar's order, then the system applies the new fee amount without a code deployment |
| AC-AP-007-016 | Given my draft satisfies all submission validation rules, when I am presented with the filing fee amount, then I can pay online before submission is completed |
| AC-AP-007-017 | Given payment is successful, then submission proceeds and I receive a payment receipt reference alongside my application ID |
| AC-AP-007-018 | Given payment fails, then the submission is not completed, the draft remains in the `Active` state, and I am informed that payment was unsuccessful |
| AC-AP-007-020 | Given a payment has already been made for this draft's submission and the submission is retried with the same command ID, then no second payment is taken |
| AC-AP-007-021 | Given the filing fee is successfully deducted but the registration application is not created due to a system failure, then the fee is refunded and the applicant is notified |
| AC-AP-007-022 | Given payment is successful, then the Registry issues a payment receipt stating the amount paid, the payment date, and the draft ID |
| AC-AP-007-023 | Given a draft fails any validation rule (BR-SB-001 to BR-SB-006), then the filing fee is not presented to the applicant until all validation errors are corrected |
| AC-AP-007-024 | Given the filing fee has been paid and the submission has been completed, then the fee is non-refundable under all circumstances including subsequent withdrawal of the application |
| AC-AP-007-025 | Given the filing fee amount, when it is presented to the applicant before payment, then the amount and the non-refundable nature of the fee are clearly stated |

---

**Payment traceability note**

*As an Applicant, I want to pay the statutory filing fee at the point of
submission so that my registration application is formally accepted.*

**References:** §12A, BR-FE-001, BR-FE-002, BR-FE-003, BR-FE-004,
BR-FE-005, BR-FE-006, BR-FE-007, BR-FE-008

This is not a separate user story or workflow. Payment is part of
`US-AP-007 — Submit a draft as a registration application`; see the
payment-related criteria in that story.

---

## Part 5 — Registration Application

### US-AP-015 — View own registration applications

*As an Applicant, I want to see a list of my registration applications and
their current status so that I can track the progress of my submissions.*

**References:** §11, §12, BR-RA-003

| ID | Acceptance Criterion |
|----|----------------------|
| AC-AP-015-001 | Given I am authenticated, when I request my registration applications, then I see all applications I submitted, regardless of their current state |
| AC-AP-015-002 | Given I have no registration applications, when I request them, then I receive an empty list |
| AC-AP-015-003 | Given I have registration applications, then each entry shows the application ID, current state, date of submission, and proposed company name |
| AC-AP-015-004 | Given an application is in a terminal state, then its final outcome is clearly indicated |
| AC-AP-015-005 | Given another applicant's applications exist, then they do not appear in my list |
| AC-AP-015-006 | Given I have both active and terminal applications, then active applications appear first ordered by submission date, and terminal applications appear below clearly labelled with their outcome |

---

### US-AP-008 — View status of own registration application

*As an Applicant, I want to see the current status of a specific registration
application so that I know where it is in the process.*

**References:** §11, §12, BR-RA-003, BR-RQ-003

| ID | Acceptance Criterion |
|----|----------------------|
| AC-AP-008-001 | Given my registration application exists, when I request it by its ID, then I receive its current state, the date it was created, and a reference to the originating draft |
| AC-AP-008-002 | Given my registration application is in the `UnderExamination` state, then I can see that it is being examined but not who the assigned Examiner is |
| AC-AP-008-003 | Given my registration application is in the `Rejected` state, when I view it, then I can see the reason for rejection as recorded by the Registrar |
| AC-AP-008-004 | Given my registration application has one or more open requisitions, when I view it, then I can see each requisition, its content, and the deadline by which I must respond |
| AC-AP-008-005 | Given I request another applicant's registration application by ID, then the request is rejected |

---

### US-AP-009 — Withdraw a registration application

*As an Applicant, I want to withdraw my registration application before a
decision is made so that I can stop the process if my circumstances change.*

**References:** §11, §12, BR-RA-003, BR-RA-004, BR-RA-005, BR-RQ-011

| ID | Acceptance Criterion |
|----|----------------------|
| AC-AP-009-001 | Given my registration application is in the `Submitted`, `UnderExamination`, `AwaitingRequisitionResponse`, or `ReadyForDecision` state, when I withdraw it, then the application transitions to the `Withdrawn` state |
| AC-AP-009-002 | Given my registration application has been withdrawn, then it is retained permanently in the Registry's records |
| AC-AP-009-003 | Given my registration application is in the `Approved`, `Rejected`, or `Withdrawn` state (all terminal states), when I attempt to withdraw it, then the request is rejected |
| AC-AP-009-004 | Given I attempt to withdraw another applicant's registration application, then the request is rejected |
| AC-AP-009-005 | Given my registration application has been withdrawn after payment of the filing fee, then the filing fee is non-refundable and no refund is issued |

---

### US-AP-010 — Receive notification of registration

*As an Applicant, I want to be notified when my approved registration
application has resulted in a registered company so that I know my company
has been registered and I have its
Registration Number.*

**References:** §14, §15, BR-RG-001, BR-RG-004

| ID | Acceptance Criterion |
|----|----------------------|
| AC-AP-010-001 | Given my registration application has been approved and `RegisteredCompanyCreated` has been recorded, then I receive a notification containing the Registration Number assigned to my company |
| AC-AP-010-002 | Given the notification is sent, then it confirms the company name, registered office address, and date of registration |
| AC-AP-010-003 | Given `RegistrationApplicationApproved` has been recorded but `RegisteredCompanyCreated` has not yet been recorded, then the registration notification is not sent |
| AC-AP-010-004 | Given a notification has already been sent for this registered company, when the notification system retries, then a duplicate notification is not sent |

---

### US-AP-011 — Receive notification of rejection

*As an Applicant, I want to be notified when my registration application is
rejected so that I understand why and can take corrective action.*

**References:** §16, BR-RA-011

| ID | Acceptance Criterion |
|----|----------------------|
| AC-AP-011-001 | Given my registration application has been rejected, then I receive a notification stating the reason for rejection |
| AC-AP-011-002 | Given the notification is sent, then it includes the application ID so I can reference it |
| AC-AP-011-003 | Given a notification has already been sent for this rejection, when the notification system retries, then a duplicate notification is not sent |

---

## Part 6 — Requisitions

### US-EX-005 — Raise a requisition

*As an Examiner, I want to raise a requisition against an application I am
examining so that I can request clarification or additional information from
the Applicant.*

**References:** §13, BR-RQ-001, BR-RQ-002, BR-RQ-003, BR-RQ-004, BR-RQ-005,
BR-RQ-008, BR-RQ-012, BR-RQ-013

| ID | Acceptance Criterion |
|----|----------------------|
| AC-EX-005-001 | Given an application is in the `UnderExamination` state and assigned to me, when I raise a requisition with a stated question and response deadline, then the application transitions to the `AwaitingRequisitionResponse` state |
| AC-EX-005-002 | Given a requisition is raised, then the applicant is notified of the requisition content and the deadline by which they must respond |
| AC-EX-005-003 | Given a requisition is raised, then the requisition is permanently recorded on the application |
| AC-EX-005-004 | Given an application is in the `AwaitingRequisitionResponse` state, when I raise a further requisition, then it is added to the open requisitions and the applicant is notified |
| AC-EX-005-005 | Given an application is not in the `UnderExamination` or `AwaitingRequisitionResponse` state, when I attempt to raise a requisition, then the request is rejected |
| AC-EX-005-006 | Given I attempt to raise a requisition on an application not assigned to me, then the request is rejected |
| AC-EX-005-007 | Given I set a requisition deadline that exceeds the maximum configured period, then the system rejects the deadline and informs me of the maximum permitted period |
| AC-EX-005-008 | Given I raised a requisition in error, when I close it with a stated reason before a response is received, then the requisition is closed, the applicant is notified, and the closure is permanently recorded |

---

### US-AP-018 — Respond to a requisition

*As an Applicant, I want to respond to a requisition raised by an Examiner
so that I can provide the requested information and my application can
proceed.*

**References:** §13, BR-RQ-006, BR-RQ-007, BR-RQ-008, BR-RQ-009

| ID | Acceptance Criterion |
|----|----------------------|
| AC-AP-018-001 | Given my application has an open requisition, when I submit a response before the stated deadline, then the response is recorded against the requisition |
| AC-AP-018-002 | Given I respond to the final open requisition, then the application transitions back to the `UnderExamination` state and the Examiner is notified |
| AC-AP-018-003 | Given I respond and there are still other open requisitions, then the application remains in the `AwaitingRequisitionResponse` state |
| AC-AP-018-004 | Given a requisition deadline passes without a response from the applicant, then the Examiner is automatically notified by the system that the deadline has passed, so that they may take appropriate action |
| AC-AP-018-005 | Given my application is not in the `AwaitingRequisitionResponse` state, when I attempt to submit a requisition response, then the request is rejected |

---

### US-EX-007 — Decide next action after a missed requisition deadline

*As an Examiner, I want to record the next action after a requisition deadline
has passed without a response so that the application does not remain
unmanaged indefinitely.*

**References:** §13, BR-RQ-009

| ID | Acceptance Criterion |
|----|----------------------|
| AC-EX-007-001 | Given I am the assigned Examiner and a requisition deadline has passed without a response, when I record a next action, then the action and reason are permanently recorded on the application |
| AC-EX-007-002 | Given the next action is to allow more time, then a new requisition deadline is recorded and the Applicant is notified |
| AC-EX-007-003 | Given the next action is to proceed toward rejection, then the application remains assigned to me until it is ready to refer to the Registrar with the missed deadline recorded in the case history |
| AC-EX-007-004 | Given I am not the assigned Examiner, when I attempt to record the next action after a missed deadline, then the request is rejected |
| AC-EX-007-005 | Given the requisition has already been answered or closed, when I attempt to record a missed-deadline next action, then the request is rejected |

---

## Part 7 — Examination

### US-EX-001 — View work queue of submitted applications

*As an Examiner, I want to see a list of submitted applications awaiting
examination so that I can select one to begin examining.*

**References:** §13, BR-RA-006, BR-RA-007

| ID | Acceptance Criterion |
|----|----------------------|
| AC-EX-001-001 | Given I am authenticated as an Examiner, when I view the work queue, then I see all registration applications in the `Submitted` state, ordered oldest first |
| AC-EX-001-002 | Given an application is in the `UnderExamination` state assigned to another Examiner, then it does not appear in the work queue |
| AC-EX-001-003 | Given an application is in the `UnderExamination`, `AwaitingRequisitionResponse`, `ReadyForDecision`, `Approved`, `Rejected`, or `Withdrawn` state, then it does not appear in the general work queue |
| AC-EX-001-004 | Given I am authenticated as an Applicant or Registrar, when I attempt to view the Examiner work queue, then the request is rejected |

---

### US-EX-002 — Begin examination of an application

*As an Examiner, I want to begin examination of a submitted application so
that it is assigned to me and removed from the general work queue.*

**References:** §13, BR-RA-006, BR-RA-007

| ID | Acceptance Criterion |
|----|----------------------|
| AC-EX-002-001 | Given an application is in the `Submitted` state, when I begin examination, then the application transitions to the `UnderExamination` state and is assigned to me |
| AC-EX-002-002 | Given an application is assigned to me, then it no longer appears in the general work queue visible to other Examiners |
| AC-EX-002-003 | Given an application is in the `UnderExamination` state assigned to another Examiner, when I attempt to begin examination, then the request is rejected |
| AC-EX-002-004 | Given an application is in any terminal state, when I attempt to begin examination, then the request is rejected |
| AC-EX-002-005 | Given I am authenticated as an Applicant or Registrar, when I attempt to begin examination, then the request is rejected |

---

### US-EX-004 — View applications assigned to me

*As an Examiner, I want to see all applications currently assigned to me so
that I can manage and resume my in-progress work.*

**References:** §13, BR-RA-006, BR-RA-007

| ID | Acceptance Criterion |
|----|----------------------|
| AC-EX-004-001 | Given I am authenticated as an Examiner, when I view my assigned applications, then I see all applications in the `UnderExamination` or `AwaitingRequisitionResponse` state that are assigned to me |
| AC-EX-004-002 | Given I have no applications assigned to me, when I view my assigned applications, then I receive an empty list |
| AC-EX-004-003 | Given applications assigned to other Examiners exist, then they do not appear in my list |
| AC-EX-004-004 | Given I am authenticated as an Applicant or Registrar, when I attempt to view assigned applications, then the request is rejected |

---

### US-EX-003 — View full application details during examination

*As an Examiner, I want to view the complete details of an application
assigned to me so that I can assess it against the Act.*

**References:** §13, BR-RA-007

| ID | Acceptance Criterion |
|----|----------------------|
| AC-EX-003-001 | Given an application is assigned to me, when I view it, then I see the proposed company name, all proposed directors and their verification status, and the registered office address |
| AC-EX-003-002 | Given an application is assigned to me, when I view it, then I see the originating draft ID and the date of submission |
| AC-EX-003-003 | Given an application is assigned to another Examiner, when I attempt to view its full details, then the request is rejected |

---

### US-EX-006 — Complete examination and refer to Registrar

*As an Examiner, I want to mark an application as fully examined and refer
it to the Registrar for decision so that the Registrar can approve or
reject it.*

**References:** §13, BR-RA-008, BR-RA-009, BR-RQ-010

| ID | Acceptance Criterion |
|----|----------------------|
| AC-EX-006-001 | Given an application is in the `UnderExamination` state and has no open requisitions, when I complete the examination and submit a recommendation note, then the application is marked as ready for the Registrar's decision |
| AC-EX-006-002 | Given the examination is completed, then the Registrar is notified and the application appears in the Registrar's decision queue |
| AC-EX-006-003 | Given an application has open requisitions, when I attempt to complete examination, then the request is rejected |
| AC-EX-006-004 | Given an application is not in the `UnderExamination` state, when I attempt to complete examination, then the request is rejected |
| AC-EX-006-005 | Given I attempt to complete examination on an application not assigned to me, then the request is rejected |

---

## Part 8 — Decision

### US-RE-001 — View cases prepared for decision

*As a Registrar, I want to see a list of applications that have been fully
examined and referred to me so that I can review and decide on them.*

**References:** §13, §14, BR-RA-010

| ID | Acceptance Criterion |
|----|----------------------|
| AC-RE-001-001 | Given I am authenticated as a Registrar, when I view the decision queue, then I see all registration applications in the `ReadyForDecision` state, ordered oldest first |
| AC-RE-001-002 | Given an application is in any state other than `ReadyForDecision`, then it does not appear in the Registrar's decision queue |
| AC-RE-001-003 | Given I am authenticated as an Applicant or Examiner, when I attempt to view the Registrar's decision queue, then the request is rejected |

---

### US-RE-002 — Approve a registration application

*As a Registrar, I want to approve a registration application that complies
with the Act so that the company is registered and enters the Register.*

**References:** §7, §10, §14, §15, BR-RA-010, BR-AP-001 to BR-AP-010,
BR-RG-001, BR-RG-002, BR-RG-003, BR-RG-004, BR-RG-005, BR-AO-001,
BR-RG-006, BR-ID-001 to BR-ID-003
**Characteristics:** legal-integrity, data-consistency, idempotency, auditability
**Fitness functions:** FF-001, FF-003, FF-004, FF-006
**ADRs:** ADR-0001, ADR-0010, ADR-0011, ADR-0017, ADR-0020

| ID | Acceptance Criterion |
|----|----------------------|
| AC-RE-002-001 | Given an application is in the `ReadyForDecision` state and satisfies all approval rules, when I approve it, then `RegistrationApplicationApproved` is recorded and the application transitions to the `Approved` state |
| AC-RE-002-002 | Given `RegistrationApplicationApproved` is recorded, then the approval process manager issues `CreateRegisteredCompany` |
| AC-RE-002-003 | Given `CreateRegisteredCompany` succeeds, then `RegisteredCompanyCreated` is recorded with a unique permanent Registration Number after final write-side name uniqueness checks pass |
| AC-RE-002-004 | Given `RegisteredCompanyCreated` is recorded, then the company exists in the Register and its record permanently references the application ID that created it |
| AC-RE-002-005 | Given the application does not state a proposed company name, when I approve it, then the approval is rejected citing BR-AP-001 |
| AC-RE-002-006 | Given the application names fewer than two proposed directors, when I approve it, then the approval is rejected citing BR-AP-002 |
| AC-RE-002-007 | Given the application names the same person as a director more than once, when I approve it, then the approval is rejected citing BR-AP-003 |
| AC-RE-002-008 | Given the application does not state a registered office address, when I approve it, then the approval is rejected citing BR-AP-004 |
| AC-RE-002-009 | Given the application names a proposed director who is not a natural person, when I approve it, then the approval is rejected citing BR-AP-005 |
| AC-RE-002-010 | Given the application names a proposed director whose verified-person record is not accepted by the Registry, when I approve it, then the approval is rejected citing BR-AP-006 |
| AC-RE-002-011 | Given the proposed company name is identical to a name already in the Register, when I approve it, then the approval is rejected citing BR-AP-007 |
| AC-RE-002-012 | Given the proposed company name is identical to a former name of a company that is within the protected period, when I approve it, then the approval is rejected citing BR-AP-010 |
| AC-RE-002-013 | Given the proposed company name falls within a prohibited category, when I approve it, then the approval is rejected citing BR-AP-008 |
| AC-RE-002-014 | Given the registered office address is not plausible, valid, or usable, when I approve it, then the approval is rejected citing BR-AP-009 |
| AC-RE-002-015 | Given an application is in any state other than `ReadyForDecision`, when I attempt to approve it, then the request is rejected citing BR-RA-010 |
| AC-RE-002-016 | Given I am authenticated as an Applicant or Examiner, when I attempt to approve an application, then the request is rejected |
| AC-RE-002-017 | Given I approve the same application twice using the same command ID, then the second request returns the previously recorded command outcome and no second registered company is created |
| AC-RE-002-018 | Given a company is registered, then each director named in the approved application is automatically recorded as an Authorised Officer of that company, with authority to act on its behalf for post-registration obligations |
| AC-RE-002-019 | Given an application is still in `UnderExamination` and has not been referred to the Registrar, when I attempt to approve it, then the request is rejected citing BR-RA-010 |
| AC-RE-002-020 | Given final write-side name uniqueness fails during `CreateRegisteredCompany`, then `RegisteredCompanyCreated` is not recorded, no Registration Number is assigned, no applicant registration notification is sent, and the approval process manager records an operational exception for manual resolution |

---

### US-RE-003 — Reject a registration application

*As a Registrar, I want to reject a registration application that does not
comply with the Act, stating the reason, so that the Applicant understands
what corrective action is needed.*

**References:** §16, BR-RA-010, BR-RA-011

| ID | Acceptance Criterion |
|----|----------------------|
| AC-RE-003-001 | Given an application is in the `ReadyForDecision` state, when I reject it with a stated reason, then the application transitions to the `Rejected` state |
| AC-RE-003-002 | Given an application is rejected, then the reason for rejection is permanently recorded on the application |
| AC-RE-003-003 | Given an application is rejected without a stated reason, then the rejection is not accepted |
| AC-RE-003-004 | Given an application is in any state other than `ReadyForDecision`, when I attempt to reject it, then the request is rejected citing BR-RA-010 |
| AC-RE-003-005 | Given I am authenticated as an Applicant or Examiner, when I attempt to reject an application, then the request is rejected |
| AC-RE-003-006 | Given an application is still in `UnderExamination` and has not been referred to the Registrar, when I attempt to reject it, then the request is rejected citing BR-RA-010 |

---

## Part 9 — Authorised Officer Obligations

### US-AO-001 — Notify the Registry of a change of director

*As an Authorised Officer, I want to notify the Registry of a change to the
company's directors so that the Register reflects the current position.*

**References:** §21, BR-RC-001, BR-RC-010, BR-RC-011, BR-AO-002,
BR-AO-003, BR-AO-004, BR-AO-006, BR-AO-007

| ID | Acceptance Criterion |
|----|----------------------|
| AC-AO-001-001 | Given the company is in the `Registered` state, when I notify the Registry of a director change within the prescribed period, then the change is recorded as an event on the company's event stream |
| AC-AO-001-002 | Given the change is recorded, then the Register read model is updated to reflect the new directors |
| AC-AO-001-003 | Given the company is in the `StruckOff` or `Dissolved` state, when I attempt to notify a director change, then the request is rejected |
| AC-AO-001-004 | Given I attempt to notify a change on a company I am not authorised to act for, then the request is rejected |
| AC-AO-001-005 | Given the director change would leave the company with fewer than two current directors, when I submit it, then the request is rejected citing BR-RC-010 |
| AC-AO-001-006 | Given the director change would add a person who is not a distinct identity-verified natural person, when I submit it, then the request is rejected citing BR-RC-011 |

---

### US-AO-002 — Notify the Registry of a change of registered office address

*As an Authorised Officer, I want to notify the Registry of a change to the
company's registered office address so that legal notices can be served at
the correct address.*

**References:** §21, BR-RC-002, BR-RC-012, BR-AO-002, BR-AO-003,
BR-AO-004

| ID | Acceptance Criterion |
|----|----------------------|
| AC-AO-002-001 | Given the company is in the `Registered` state, when I notify the Registry of an address change within the prescribed period, then the change is recorded as an event on the company's event stream |
| AC-AO-002-002 | Given the change is recorded, then the Register read model is updated to reflect the new registered office address |
| AC-AO-002-003 | Given the company is in the `StruckOff` or `Dissolved` state, when I attempt to notify an address change, then the request is rejected |
| AC-AO-002-004 | Given I attempt to notify a change on a company I am not authorised to act for, then the request is rejected |
| AC-AO-002-005 | Given the new registered office address is not plausible, valid, within the jurisdiction, or usable for service of legal notices, when I submit the change, then the request is rejected citing BR-RC-012 |

---

### US-AO-003 — File an annual return

*As an Authorised Officer, I want to file the company's annual return with
the Registry so that the company complies with its legal obligations.*

**References:** §22, BR-RC-003, BR-RC-013, BR-RC-014, BR-RC-015,
BR-AO-002, BR-AO-003, BR-AO-004

| ID | Acceptance Criterion |
|----|----------------------|
| AC-AO-003-001 | Given the company is in the `Registered` state, when I file an annual return in the prescribed form, then it is recorded as an event on the company's event stream |
| AC-AO-003-002 | Given an annual return is filed, then it is retained permanently in the Registry's records |
| AC-AO-003-003 | Given the company is in the `StruckOff` or `Dissolved` state, when I attempt to file an annual return, then the request is rejected |
| AC-AO-003-004 | Given I attempt to file on behalf of a company I am not authorised to act for, then the request is rejected |
| AC-AO-003-005 | Given the annual return is not in the prescribed form, when I attempt to file it, then the request is rejected |
| AC-AO-003-006 | Given an annual return has already been filed for the same company and return period, when I attempt to file another return for that period, then the request is rejected |
| AC-AO-003-007 | Given the annual return form or filing period is changed by the Registrar's order, then the system applies the new configuration without a code deployment |

---

### US-AO-004 — Apply for voluntary dissolution

*As an Authorised Officer, I want to apply to the Registrar to dissolve the
company voluntarily so that it can be lawfully wound up.*

**References:** §24, BR-RC-005, BR-RC-006, BR-AO-002, BR-AO-003

| ID | Acceptance Criterion |
|----|----------------------|
| AC-AO-004-001 | Given the company is in the `Registered` state, when I submit a dissolution application, then the application is recorded and the Registrar is notified for decision |
| AC-AO-004-002 | Given the company has outstanding obligations in this micro-registry contract — unfiled annual returns or unpaid fees owed to the Registry — when I attempt to apply for dissolution, then the application is rejected stating which obligations remain |
| AC-AO-004-003 | Given I attempt to apply for dissolution on a company I am not authorised to act for, then the request is rejected |
| AC-AO-004-004 | Given the company is in the `StruckOff` or `Dissolved` state, when I attempt to apply for dissolution, then the request is rejected |

---

## Part 10 — Registered Company Lifecycle (Registrar)

### US-RE-006 — View a registered company

*As a Registrar, I want to view the full details of a registered company,
including its current status and history, so that I can make informed
decisions about compliance and strike-off.*

**References:** §17, §18, §20, §23, BR-RI-001

| ID | Acceptance Criterion |
|----|----------------------|
| AC-RE-006-001 | Given a company exists in the Register, when I request it by Registration Number, then I receive its full registration details including Registration Number, company name, registered office address, directors, date of registration, and current state |
| AC-RE-006-002 | Given a company has had director or address changes since registration, when I view it, then I can see the current details as well as the history of changes |
| AC-RE-006-003 | Given a company has been struck off or dissolved, when I view it, then I can see the date and reason for strike-off or dissolution |
| AC-RE-006-004 | Given a Registration Number does not exist, when I request it, then I receive a not-found response |
| AC-RE-006-005 | Given I am authenticated as an Applicant, Examiner, or unauthenticated user, then the Registrar's detailed view is not accessible to me |

---

### US-RE-004 — Strike off a registered company

*As a Registrar, I want to strike off a registered company that has failed
to comply with its obligations so that it ceases to exist as a legal entity.*

**References:** §23, BR-RC-004, BR-RC-007, BR-RC-009, BR-RG-003, BR-AO-005

| ID | Acceptance Criterion |
|----|----------------------|
| AC-RE-004-001 | Given a registered company is in the `Registered` state, when I strike it off, then the company transitions to the `StruckOff` state |
| AC-RE-004-002 | Given a company has been struck off, then it ceases to be a legal entity from the date of strike-off |
| AC-RE-004-003 | Given a company has been struck off, then it remains in the historical record of the Register |
| AC-RE-004-004 | Given a company has been struck off, then its Registration Number is not reused |
| AC-RE-004-005 | Given a company is in the `StruckOff` or `Dissolved` state, when I attempt to strike it off, then the request is rejected |
| AC-RE-004-006 | Given I am authenticated as an Applicant or Examiner, when I attempt to strike off a company, then the request is rejected |

---

### US-RE-005 — Process a dissolution application

*As a Registrar, I want to process a voluntary dissolution application from
an Authorised Officer so that the company can be lawfully wound up.*

**References:** §24, BR-RC-005, BR-RC-006, BR-RC-008, BR-RC-009, BR-RG-003

| ID | Acceptance Criterion |
|----|----------------------|
| AC-RE-005-001 | Given a registered company is in the `Registered` state and has no outstanding obligations, when I direct dissolution, then the company transitions to the `Dissolved` state |
| AC-RE-005-002 | Given a company has outstanding obligations in this micro-registry contract — unfiled annual returns or unpaid fees owed to the Registry — when I attempt to direct dissolution, then the request is rejected stating which obligations remain outstanding |
| AC-RE-005-003 | Given a company has been dissolved, then it ceases to be a legal entity from the date of dissolution |
| AC-RE-005-004 | Given a company has been dissolved, then it remains in the historical record of the Register |
| AC-RE-005-005 | Given a company has been dissolved, then its Registration Number is not reused |
| AC-RE-005-006 | Given a company is in the `StruckOff` or `Dissolved` state, when dissolution is attempted, then the request is rejected |
| AC-RE-005-007 | Given I am authenticated as an Applicant or Examiner, when I attempt to direct dissolution, then the request is rejected |

---

## Part 11 — Public Register

### US-PB-001 — Search the Register by company name

*As a member of the Public, I want to search the Register by company name
so that I can find information about registered companies.*

**References:** §19, BR-RI-001, BR-RI-002, BR-RI-003, BR-RI-008

| ID | Acceptance Criterion |
|----|----------------------|
| AC-PB-001-001 | Given the Register contains registered companies, when I search by company name, then I receive a list of companies whose names match or contain my search term |
| AC-PB-001-002 | Given a company has been struck off or dissolved, then it still appears in search results with its status clearly indicated |
| AC-PB-001-003 | Given no companies match my search term, then I receive an empty result set |
| AC-PB-001-004 | Given I am unauthenticated, then I may still search the Register |

---

### US-PB-002 — View a registered company by Registration Number

*As a member of the Public, I want to view a registered company's details
by its Registration Number so that I can verify its legal existence and
current particulars.*

**References:** §17, §18, §19, BR-RI-001, BR-RI-002, BR-RI-003, BR-RI-007

| ID | Acceptance Criterion |
|----|----------------------|
| AC-PB-002-001 | Given a company is registered, when I request it by Registration Number, then I receive its Registration Number, company name, current registered office address, date of registration, and current directors |
| AC-PB-002-002 | Given a company has been struck off or dissolved, when I request it by Registration Number, then I receive its details with its current status and the date of strike-off or dissolution |
| AC-PB-002-003 | Given a Registration Number does not exist in the Register, when I request it, then I receive a not-found response |
| AC-PB-002-004 | Given I am unauthenticated, then I may still view company details from the Register |

---

### US-PB-003 — Search the Register by director name

*As a member of the Public, I want to search the Register by director name
so that I can find companies associated with a particular person.*

**References:** §19, BR-RI-001, BR-RI-002, BR-RI-003

| ID | Acceptance Criterion |
|----|----------------------|
| AC-PB-003-001 | Given the Register contains companies with latest recorded directors, when I search by director name, then I receive companies whose latest recorded directors match or contain my search term |
| AC-PB-003-002 | Given a matching company has been struck off or dissolved, then it still appears in search results with its status clearly indicated and its latest recorded directors are used for matching |
| AC-PB-003-003 | Given no latest recorded directors match my search term, then I receive an empty result set |
| AC-PB-003-004 | Given I am unauthenticated, then I may still search the Register by director name |
| AC-PB-003-005 | Given a person was formerly but is no longer a director of a company, then that historical association is not returned by the basic director-name search |

---

## Part 12 — System Processes

### US-SY-001 — Automatically expire drafts

*As the Registry system, I want to automatically expire active drafts that
have exceeded the configured expiry period so that the Act's requirement is
enforced without manual intervention.*

**References:** §5, BR-DR-003, BR-DR-004, BR-DR-008, BR-DR-009, BR-DR-010,
BR-AU-004 to BR-AU-007

| ID | Acceptance Criterion |
|----|----------------------|
| AC-SY-001-001 | Given a draft is in the `Active` state and its age exceeds the configured expiry threshold, when the expiry scheduler runs, then an `ExpireDraft` command is issued to the Decider for that draft |
| AC-SY-001-002 | Given the `ExpireDraft` command is accepted by the Decider, then the draft transitions to the `Expired` state |
| AC-SY-001-003 | Given an expired draft, then it no longer counts towards the applicant's limit of 5 active drafts |
| AC-SY-001-004 | Given an expired draft, then it is retained permanently in the Registry's records |
| AC-SY-001-005 | Given the expiry threshold is changed by the Registrar's order, then the scheduler uses the new configured value without a code deployment |
| AC-SY-001-006 | Given the scheduler runs and finds no drafts exceeding the expiry threshold, then no commands are issued |
| AC-SY-001-007 | Given the scheduler crashes and restarts, when it runs again, then drafts that were not successfully expired on the previous run are expired on the next run |
| AC-SY-001-008 | Given the same draft would be expired twice — for example due to a scheduler retry — when the `ExpireDraft` command is issued a second time with the same command ID, then the previous command outcome is returned and no second event is recorded |

---

### US-SY-002 — Notify Applicant before draft expiry

*As the Registry system, I want to notify an Applicant when their draft is
approaching its expiry date so that they have the opportunity to submit or
cancel before it expires.*

**References:** §5, BR-DR-003, BR-DR-012

| ID | Acceptance Criterion |
|----|----------------------|
| AC-SY-002-001 | Given a draft is in the `Active` state and is within the configured warning period before expiry, when the notification scheduler runs, then the Applicant receives a draft-expiry warning |
| AC-SY-002-002 | Given the warning period is changed by the Registrar's order, then the scheduler uses the new configured value without a code deployment |
| AC-SY-002-003 | Given a draft has already had an expiry warning sent for its current expiry date, when the scheduler runs again, then a duplicate warning is not sent |
| AC-SY-002-004 | Given a draft is in the `Submitted`, `Cancelled`, or `Expired` state, then no draft-expiry warning is sent |
| AC-SY-002-005 | Given the scheduler crashes and restarts, then any due expiry warnings not sent on the previous run are sent on the next run |

---

### US-SY-003 — Notify Examiner when a requisition deadline passes

*As the Registry system, I want to notify an Examiner when a requisition
deadline has passed without an Applicant response so that the Examiner can
take appropriate action.*

**References:** §13, BR-RQ-009

| ID | Acceptance Criterion |
|----|----------------------|
| AC-SY-003-001 | Given a requisition deadline passes and no response has been recorded, when the deadline scheduler runs, then the assigned Examiner receives a notification stating the application ID, the requisition content, and the date the deadline passed |
| AC-SY-003-002 | Given the deadline notification has already been sent for a specific requisition, when the scheduler runs again, then a duplicate notification is not sent |
| AC-SY-003-003 | Given the application has been withdrawn before the deadline passes, then no notification is sent |
| AC-SY-003-004 | Given the scheduler crashes and restarts, then any overdue notifications not sent on the previous run are sent on the next run |

---

## Out of Scope — Explicitly Excluded from this Contract

The following items were considered during requirements analysis and are
explicitly excluded from the scope of this contract.

| Item | Reason |
|------|--------|
| Examiner reassignment | Requires a Supervisor actor not modelled in this contract. Deferred to Phase 2. |
| Identity verification revocation | Significant legal implications affecting all companies a director sits on. Separate piece of work. |
| Identity proofing and proposed-director outreach | This contract requires verified identity flags and records, but does not perform the identity-proofing ceremony or run the future parallel workflow that contacts unverified proposed directors during an application. |
| Notification preferences | Email only, using address registered with identity broker. |
| Payment gateway integration | Technical dependency resolved during implementation. |
| Company name fuzzy matching | Prohibited name categories (BR-AP-008) cover similarity rules. Exact match only for uniqueness. |
| Name reservation and name locking | Parked pending a fuller legal and product design. Future scope may check name availability during draft preparation, lock the name on successful submission, and release the lock when the application reaches a terminal state. |
| Fuller outstanding-obligations model | The current micro-registry blocks dissolution for unfiled annual returns and unpaid fees only. Future scope should consider overdue director or office notifications, unresolved compliance action, pending strike-off proceedings, penalties, and other statutory debts. |

---

## Non-Functional Requirements

*Traceability summary for architectural constraints derived from §20 and the
audit business rules. The full architectural characteristics, dependency
failure expectations, and mandatory fitness functions are maintained in
[06-architectural-characteristics.md](06-architectural-characteristics.md).*

**References:** §20, BR-RI-001, BR-RI-002, BR-RI-004, BR-RI-005, BR-RI-006,
BR-ID-001 to BR-ID-005, BR-AU-001 to BR-AU-008

| ID | Requirement |
|----|-------------|
| NFR-001 | The Register is the event store — the set of `RegisteredCompany` event streams containing a `RegisteredCompanyCreated` event. The `register` table is a read model projection, not the Register itself. *(BR-RI-001, BR-RI-002)* |
| NFR-002 | The Register read model must be derived exclusively from domain events. No direct writes to the Register read model table are permitted outside the Register projector. *(BR-RI-005)* |
| NFR-003 | If the Register read model is destroyed, it must be fully rebuildable by replaying domain events from the event store. No company ceases to exist as a result. *(BR-RI-001, BR-RI-002, BR-RI-005)* |
| NFR-004 | Company name uniqueness must be enforced in the authoritative write-side Register path before `RegisteredCompanyCreated` is recorded. A read-model unique constraint may exist only as defensive projection protection. *(BR-RI-006)* |
| NFR-005 | Every event written to the event store must permanently record: the identity of the person who caused it, the date and time, the causation ID, and the correlation ID. *(BR-AU-005, BR-AU-006, BR-AU-007)* |
| NFR-006 | No event may ever be altered or deleted from the event store once written. *(BR-AU-008)* |
| NFR-007 | All drafts, registration applications, and registered company records must be retained permanently in the event store regardless of status. *(BR-AU-001, BR-AU-002, BR-AU-003)* |
| NFR-008 | The Registry must maintain a complete and tamper-evident audit record of all events across all aggregates. *(BR-AU-004, §20)* |
| NFR-009 | Command idempotency must be enforced by a command ledger keyed by command ID. Event causation ID is traceability metadata and must not be globally unique across the event store. *(BR-ID-001 to BR-ID-005)* |
