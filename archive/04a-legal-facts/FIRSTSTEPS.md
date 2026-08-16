# First Steps — 04a-legal-facts

Setup plan for getting from a documentation-only project to a first passing test,
using `04-legal-facts` as the reference implementation.

---

## Step 1 — Read the golden thread in order (do not skip)

Before touching any code, read the docs in sequence. Each one gates the next:

| # | Document | Why |
|---|----------|-----|
| 1 | [`00-companies-registration-act.md`](docs/00-companies-registration-act.md) | The law is the requirements document. All rules and stories trace here. |
| 2 | [`04-domain-discovery.md`](docs/04-domain-discovery.md) | Establishes the ubiquitous language. Code will use these names. |
| 3 | [`07-business-rules.md`](docs/07-business-rules.md) | FSMs in EDN; these drive the Decider tests. |
| 4 | [`08-user-stories.md`](docs/08-user-stories.md) | Pick the first story. US-AP-002 "Create a draft" is the natural start. |
| 5 | [`09-testing-strategy.md`](docs/09-testing-strategy.md) | Understand the five test layers before writing a single test. |

---

## Step 2 — Agree scope before the first line of implementation

Per [`11-definition-of-done.md`](docs/11-definition-of-done.md), the Definition of Done
is *Proposed — requires customer sign-off before development begins.*

Run the scope agreement ceremony:

1. Review `08-user-stories.md` together. Confirm every story traces to the Act.
2. Agree which stories are in scope and which are parked.
3. Both parties sign `11-definition-of-done.md` and `08-user-stories.md`.

Once signed, the question "is it done?" has an unambiguous shared answer.
Do not start Step 3 until this is complete.

---

## Step 3 — Scaffold the project (using 04 as reference)

Create the following structure, copying from `04-legal-facts` and adjusting for this project:

```
04a-legal-facts/
  bb.edn                          # task automation (copy from 04, update volume name)
  docker-compose.yml              # Postgres + Flyway migrate service (copy from 04)
  mise.toml                       # tool versions (copy from 04 as-is)
  backend/
    deps.edn                      # Clojure deps including Flyway (copy from 04)
    src/
      registry/                   # Clojure source root
    test/
      registry/                   # Clojure test root
    resources/
      config.edn                  # aero config (db connection, server port)
      db/migration/               # Flyway SQL migration files (V1__*.sql, V2__*.sql …)
```

### Migration tool: Flyway

Migrations use **Flyway**, matching `04-legal-facts`. ADR-0003 specifies Postgres
as the database but leaves the migration tool undecided; Flyway is used here for
consistency with the reference implementation.

`deps.edn` must include:

```clojure
org.flywaydb/flyway-core                    {:mvn/version "12.6.2"}
org.flywaydb/flyway-database-postgresql     {:mvn/version "12.6.2"}
```

Flyway is run as a Docker Compose service (`migrate`) that runs and exits before
the backend starts. The `docker-compose.yml` `depends_on` condition ensures the
backend waits for migrations to complete.

---

## Step 4 — First TDD cycle: US-AP-002 "Create a draft"

Follow the loop in [`DEVELOPMENT-WORKFLOW.md`](docs/DEVELOPMENT-WORKFLOW.md):
Red → Green → Refactor → CI. Repeat for each acceptance criterion.

### Phase 1 — Red (Discovery and API Design)

Write a **Layer 1 pure-function test** for the `decide` function. No Postgres,
no HTTP, no infrastructure of any kind.

Target: `backend/test/registry/draft/decider_test.clj`

```clojure
(deftest AC-AP-002-001-create-draft-active-state
  (let [result (decide {:state   :new
                        :drafts  0}
                       {:command/type  :create-draft
                        :applicant-id  "applicant-1"})]
    (is (= :draft/created (:event/type result)))
    (is (= :active        (-> result :data :state)))))
```

Run it. It must fail. The safety net is now armed.

### Phase 2 — Green (Implementation)

Implement the bare minimum Decider to make the test pass. Write sinful code —
speed to green dominates everything else at this phase.

Target: `backend/src/registry/draft/decider.clj`

```clojure
(defn decide [state command]
  (case (:command/type command)
    :create-draft {:event/type :draft/created
                   :data       {:state :active}}))
```

Run the test. It must pass.

### Phase 3 — Refactor (Preservation)

Clean up the implementation. No new tests. The Layer 1 test stays green
throughout. Add the FSM guard from `07-business-rules.md` (maximum 5 active
drafts, identity verification check).

### Phase 4 — CI

Wire `bb test:backend` to run all layers. Push to trunk. The CI gate must be
green before moving to the next acceptance criterion.

---

## Testcontainers setup (for Layer 2 adapter tests)

Layer 2 tests use Testcontainers to spin up a real Postgres instance. Rancher
Desktop requires a one-time machine setup:

**`~/.docker-java.properties`**
```
api.version=1.44
```

**`~/.testcontainers.properties`**
```
docker.host=unix:///Users/$USER/.rd/docker.sock
ryuk.disabled=true
checks.disable=true
```

Ensure Rancher Desktop is using **dockerd (moby)**, not containerd.

These are Layer 2 prerequisites only. Layer 1 tests run without Docker.

---

## After the first cycle

Once US-AP-002 AC-AP-002-001 is green in CI, the loop repeats for each
remaining acceptance criterion in US-AP-002, then for each subsequent story.

The order to follow:

1. US-AP-002 — Create a draft (Layer 1 Decider tests first)
2. US-AP-003 — Update a draft
3. US-AP-004 — Cancel a draft
4. US-AP-005 — List drafts (first Layer 2 adapter test + projection)
5. US-AP-007 — Submit a draft (triggers the RegistrationApplication context)

Add Layer 2 (adapter), Layer 3 (behavioural/HTTP), Layer 4 (fitness functions),
and Layer 5 (contract) tests as each story's scope demands them.
