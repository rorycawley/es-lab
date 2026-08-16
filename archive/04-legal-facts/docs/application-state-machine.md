# Application State Machine

Derived directly from [companies-registration-act.md](companies-registration-act.md). Every state and transition traces to a legal source.

---

## States and transitions

```
                    [Draft]
                       │
              applicant submits
                       │
                  [Submitted]
                  │        │
         examiner         applicant
         picks up         withdraws
                  │              │
          [Under Examination]    │
          │       │       │
   registrar   registrar  applicant
   approves    rejects    withdraws
          │       │       │
   [Registered] [Rejected] [Withdrawn]
```

---

## States

| State | Legal source | Note |
|---|---|---|
| Draft | Practical | Not in the Act; requires lawyer sign-off |
| Submitted | CRA §2 | Application formally lodged with the Registry |
| Under Examination | CRA §4 | Registry is examining for compliance |
| Withdrawn | CRA §7 | Application voluntarily closed by applicant |
| Rejected | CRA §6 | Application decision — does not comply |
| Registered | CRA §5 + §8 | The company legally exists in the register |

---

## Transitions

The conditions column is what a lawyer reviews to confirm the system enforces the law. Every row maps to a command in [commands-and-events.md](commands-and-events.md).

| ID | Current state | Command | Who | Conditions | Next state | CRA |
|---|---|---|---|---|---|---|
| T1 | — | `create-registration-application` | Applicant | — | Draft | Practical |
| T2 | Draft | `submit-registration-application` | Applicant | Name stated; at least two proposed directors named; registered office address stated; all proposed directors identity-verified by the Registry as natural persons | Submitted | §2, §3 |
| T3 | Submitted | `begin-examination` | Examiner | — | Under examination | §4 |
| T4 | Submitted | `withdraw-registration-application` | Applicant | No decision yet made | Withdrawn | §7 |
| T5 | Under examination | `withdraw-registration-application` | Applicant | No decision yet made | Withdrawn | §7 |
| T6 | Under examination | `approve-registration-application` | Registrar | Application complies with the Act; registered office address is plausible and valid | Registered | §4, §5, §8 |
| T7 | Under examination | `reject-registration-application` | Registrar | Application does not comply with the Act; reason must be stated | Rejected | §6 |

---

## Notes

**Draft is not in the law.** It is a practical necessity — an application must exist somewhere before it is submitted. A lawyer should confirm this is acceptable. If not, the lifecycle begins at Submitted.

**Withdrawal is permitted at any time before a decision.** CRA §7 says "at any time before a decision is made." A decision is the Registrar approving (§5) or rejecting (§6). This means withdrawal is possible from both Submitted and Under Examination. The diagram shows both paths.

**Approval and registration are two different facts in one transaction.** Per CRA §5 they happen together — "upon approval, the company is registered and enters the Register" — so there is no externally observable `Approved` state. The `approve-registration-application` command atomically records both facts:

- `registration-application-approved` — the application decision
- `company-registered` — the legal existence of the company

This distinction matters for the event log. The application decision and the company's legal existence are not the same fact, even though they are committed together.
