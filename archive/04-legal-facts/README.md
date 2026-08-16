# 04-legal-facts

A micro companies registry for a single company type — private limited company — and a single lifecycle event: registration. The legal instrument driving the design is [companies-registration-act.md](docs/companies-registration-act.md).

Reference documents:

- [ubiquitous-language.md](docs/ubiquitous-language.md) — shared terms used by the domain, API, tests, and docs
- [companies-registration-act.md](docs/companies-registration-act.md) — fictional legal instrument
- [application-state-machine.md](docs/application-state-machine.md) — states and legal transitions
- [business-rules.md](docs/business-rules.md) — rule catalogue mapped to transition IDs
- [commands-and-events.md](docs/commands-and-events.md) — command/event catalogue

## Three ideas this project is built on

### 1. The law is the requirements document

Every constraint on what can be registered traces to a section of the Act. This eliminates ambiguity: if a requirement cannot be pointed to in the law, it is an assumption that must be justified.

| Requirement | Legal basis |
|---|---|
| An application must state a company name | CRA §2 |
| An application must name at least two proposed directors | CRA §2 |
| An application must state a registered office address | CRA §2 |
| Every proposed director must be identity-verified by the Registry as a natural person | CRA §3 |
| The registered office address must be plausible and valid before approval | CRA §4, §5 |
| The Registry shall examine every application | CRA §4 |
| The Registrar shall approve a compliant application; the company is registered | CRA §5 |
| The Registrar shall reject a non-compliant application, stating the reason | CRA §6 |
| An applicant may withdraw at any time before a decision | CRA §7 |
| The register is the authoritative legal record | CRA §8 |

When the law changes, the impact is locatable. When a requirement has no citation, that is a flag, not a detail.

### 2. Registration is a finite state machine

The state machine is not invented. It is read from the law and transcribed in order:

```
Law / Legal Instrument
  → State Machine  (what states can a thing be in; what transitions are legal)
    → Commands     (what actions trigger transitions)
      → Events     (what gets recorded when a command succeeds)
        → Functions (the code that enforces it)
```

Each layer is reviewable by a different person:

- A **lawyer** can review the state machine against the law
- A **business analyst** can review the commands against the state machine
- A **developer** can review the functions against the commands

When the law changes, the impact traces the same path — no hunting through code:

```
Law changes
  → Does the state machine change?
    → Do the commands change?
      → Do the functions change?
```

Before writing any code, draw the state machine on paper or a whiteboard with a lawyer or senior business analyst present and have them sign off on it. That drawing is the legal specification for the aggregate.

Two consequences of following this order:

- **Implementation is a direct translation**: the state machine is defined by law, not invented; there is no ambiguity about what should happen
- **Testing is complete by construction**: enumerate every valid transition and every invalid one; if all pass, the implementation is correct

```
                    [Draft]
                       │
              applicant submits
                       │
                  [Submitted]
                  │        │
         examiner         applicant
         begins           withdraws
         examination        │
                  │         │
          [Under Examination]
          │       │       │
   registrar   registrar  applicant
   approves    rejects    withdraws
          │       │       │
   [Registered] [Rejected] [Withdrawn]
```

For each transition, three questions matter: **who** can trigger it, **what conditions** must hold, and **what is the legal effect?** The key transition illustrates how much is encoded in a single arrow:

| | `under-examination → registered` |
|---|---|
| Who | Registrar only (CRA §5) |
| Conditions | Examination complete (CRA §4); at least two proposed directors named (CRA §2); every proposed director identity-verified as a natural person (CRA §3); registered office stated (CRA §2); registered office plausible and valid (CRA §4) |
| Legal effect | The application is approved and the company is registered in one transaction (CRA §5) |

Full transition table:

| From | To | Who | CRA | Legal meaning |
|---|---|---|---|---|
| (none) | draft | applicant | — | No legal effect |
| draft | submitted | applicant | §2 | Binding — the registry must process it |
| submitted | withdrawn | applicant | §7 | Process voluntarily closed |
| submitted | under-examination | examiner | §4 | Examination has begun |
| under-examination | withdrawn | applicant | §7 | Process voluntarily closed before decision |
| under-examination | registered | Registrar | §4, §5, §8 | Application approved; company legally exists |
| under-examination | rejected | Registrar | §6 | Process closed; reason recorded |

Any other transition returns `422`. The state machine enforces this — no caller can bypass it.

**How the state machine works in practice**

The state machine is how the aggregate decides whether to accept a command at all. When the aggregate replays its events, it rebuilds its current state — which is a position in the state machine. That position is checked first, before any business rules.

Commands are requests. Events are facts. The flow for every command has two stages:

```
Command arrives
  → Aggregate replays events → current state = position in the state machine
    → Stage 1: is this command allowed in the current state?
      → No  → rejected immediately; no business rules checked
      → Yes → Stage 2: are the business rules satisfied?
                → No  → rejected; nothing recorded
                → Yes → emit event → store it → state updated
```

A concrete example — the Registrar approves an application:

```
approve-registration-application arrives
  → Replay events → current state = [under-examination]
    → State machine: is approve-registration-application allowed in [under-examination]? ✓
      → Business rules: name still available? ✓  at least two proposed directors? ✓  all proposed directors verified as natural persons? ✓  address valid? ✓
        → Emit `registration-application-approved` and `company-registered` atomically
          → create CompanyRecord

Same command, application in [withdrawn]:
  → State machine: is approve-registration-application allowed in [withdrawn]? ✗
    → Rejected immediately. No business rules checked.
```

The same pattern will apply after registration in later projects. The company's lifecycle state gates which commands are even reachable. The following example is illustrative only and is not part of this project's command/event catalogue:

```
In [registered]:         change-director ✓  (proceed to business rules)
In [struck-off]:         change-director ✗  (rejected before any rules checked)
In [under-examination]:  change-director ✗  (not applicable during application)
```

`change-director`, `change-registered-office`, `file-annual-return`, and `strike-off-company` are future post-registration commands. They are shown here only to illustrate that lifecycle state gates later company changes.

The state machine is a first-class object in the aggregate, not an implicit side effect of if-statements. Here is what that looks like in Clojure.

The current registration workflow's allowed commands per state — readable enough for a business analyst to review:

```clojure
(def allowed-commands
  {nil                #{:create-registration-application}
   :draft             #{:submit-registration-application}
   :submitted         #{:begin-examination
                        :withdraw-registration-application}
   :under-examination #{:approve-registration-application
                        :reject-registration-application
                        :withdraw-registration-application}
   :registered        #{}})

(defn command-allowed? [current-state command-type]
  (contains? (get allowed-commands current-state #{}) command-type))
```

State rebuilt by replaying events — pure, no database:

```clojure
(defmulti apply-event (fn [_state event] (:event/type event)))

(defmethod apply-event :registration-application-created [state _event]
  (assoc state :status :draft))

(defmethod apply-event :registration-application-submitted [state _event]
  (assoc state :status :submitted))

(defmethod apply-event :examination-started [state _event]
  (assoc state :status :under-examination))

(defmethod apply-event :registration-application-approved [state _event]
  state)

(defmethod apply-event :company-registered [state _event]
  (assoc state :status :registered))

(defmethod apply-event :registration-application-rejected [state _event]
  (assoc state :status :rejected))

(defmethod apply-event :registration-application-withdrawn [state _event]
  (assoc state :status :withdrawn))

(defn rebuild-state [events]
  (reduce apply-event {:status nil} events))
```

The command handler — checks state machine first, then business rules, returns events without touching storage:

```clojure
(defn handle-command [events command]
  (let [state        (rebuild-state events)
        command-type (:command/type command)]
    (if-not (command-allowed? (:status state) command-type)
      {:error :command-not-allowed-in-current-state}
      (case command-type
        :create-registration-application (handle-create state command)
        :submit-registration-application (handle-submit state command)
        :begin-examination (handle-begin-examination state command)
        :withdraw-registration-application (handle-withdraw state command)
        :approve-registration-application (handle-approval state command)
        :reject-registration-application (handle-rejection state command)
        ))))
```

Three things to notice:
- `rebuild-state` is pure — no database, no side effects
- The state machine check happens before business rules
- `handle-command` never touches storage — the caller stores the returned events

**Why not a config file?**

The `allowed-commands` map looks like config, so it is tempting to move it to YAML so non-developers can edit transitions when the law changes. Don't.

The state machine is not the hard part. The hard part is the business rule functions attached to each transition — things like `identity-verified?`, `at-least-two-directors-remain?`, `name-available?`. Those cannot live in a config file. They are code. So a config file controls the transitions, but the real logic is still in code. More complexity, no fewer developers needed.

When the law changes, you are not just editing a transition. You are changing allowed states, transition conditions, events emitted, projections that build the register, and tests that prove compliance. That needs a code review and a deployment regardless. A config file gives a false sense of agility.

Keep `allowed-commands` in code, where it is version-controlled, reviewed, and tested alongside everything else. The one exception: if the registry has dozens of similar company types with slightly different rules, a data-driven approach for that variation might make sense — but that is a different problem.

**Two levels of state**

Not all events change the lifecycle state. The state machine operates at two levels:

- **Lifecycle state** — `Registered`, `Struck Off`, `Dissolved` — the major legal transitions shown in the diagram; determines what commands are legal
- **Data within a state** — directors, addresses, filings — changes that occur while the company remains in a lifecycle state

Events affect both, but differently. `company-registered` changes the lifecycle state. A later `director-changed` event, from a future project, would change the company's data without changing its lifecycle state — the company stays `Registered`. Both are legal events; neither can be undone.

> Events record what *legally happened*. Commands are just requests. A command can be rejected. An event, once stored, cannot be undone — only compensated by a subsequent event.

### 3. The process and the legal fact are two different things

The register is not a record of reality. It *is* the reality. A company exists in law because the register says so.

There are two questions to answer. Both are answered from the event store, but
at different levels of interpretation:

**What happened?** (the process — one row per event)
```
registration-application-created
registration-application-submitted
examination-started
registration-application-approved
company-registered
```

**What is legally true now?** (the Register — the current legal facts)
```clojure
{:company/registration-number "IE-2026-001234"
 :company/name               "Acme Ltd"
 :company/type               :private-limited
 :company/status             :registered
 :company/registered-office  {...}
 :company/registered-on      #inst "2026-05-24"}
```

Someone searching the Register does not need to know about `examination-started`.
They need to know the company exists and its current details. That current view
is obtained by interpreting the authoritative event stream.

A possible implementation shape stores the legal source of truth separately from
derived query models:

| Table | Contains | Mutability |
|---|---|---|
| `registration_events` | The legal Register and process history — facts that happened, including `company-registered` | Append-only |
| `register_projection` | A query model rebuilt from `company-registered` events | Projection; eventually consistent |

The event store carries the durable integrity requirements. For example, company-name uniqueness is enforced by a partial unique index on `company-registered` events, not by checking the asynchronous `register_projection` table.

---

## Architecture

This directory is a domain-design exercise with an executable reference implementation. It defines the legal instrument, ubiquitous language, state machine, business rules, commands, and events, then verifies the implementation against those documents.

Runtime endpoints:

| URL | Purpose |
|---|---|
| `http://localhost:8080/health` | Backend health endpoint |
| `http://localhost:8080/openapi.json` | OpenAPI 3.0 spec |
| `http://localhost:8080/swagger-ui` | Interactive API browser |

The backend API contract is tested in two layers, following the pattern from project 03:

- OpenAPI integration tests verify that the published machine-readable API contains the expected paths, request schemas, examples, and documentation endpoints.
- Pact-style consumer-driven contract tests define consumer interactions as data and verify the running provider over HTTP. They do not require a Pact broker yet; the point is to make consumer expectations explicit before a separate UI or external client exists.

## Open design questions

- Applicant natural-person verification is retained as BR-001, but parked because this project has no identity-provider model.
- Name reservation is retained as BR-009, but parked because this project only checks name uniqueness at the atomic approval/registration step.
- Role enforcement is described in the domain language, but authentication and authorisation are outside this document set.
- Post-registration changes, event sourcing, and fraud correction are future topics; this project only models the initial registration legal fact.
