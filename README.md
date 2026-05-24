# es-lab

> **Enduring software:** software that continues to meet the needs of institutions over time.

**es-lab** - *Enduring Software Laboratory* - is a collection of isolated mini-projects for building secure, configurable, full-stack systems that can run anywhere: laptop, on-premises, or public cloud.

The purpose is not to collect tools. The purpose is to prove architectural ideas through working software.

---

## Why this exists

Institutions need software that lasts.

A registry, bank, hospital, university, insurer, utility, or public body cannot casually replace core systems every few years. These systems hold important data, support important decisions, and often serve people who have no choice but to trust them.

That trust is fragile.

Software that is insecure, unreliable, opaque, expensive to change, or tied too tightly to one vendor will eventually become a risk to the institution it was meant to serve.

**es-lab** exists to explore how to build software that can endure change without becoming dangerous, obsolete, or impossible to maintain.

---

## What institutions value

Institutional software is judged by different standards from short-lived product experiments.

Institutions value software that is:

### Secure

It protects confidentiality, integrity, availability, privacy, and trust.

Security is not a feature added at the end. It is part of the design of the domain model, API, identity model, deployment model, and operating model.

### Changeable

It can adapt to new laws, policies, workflows, forms, rules, integrations, and data models without requiring a rewrite.

Change should be deliberate, versioned, testable, and reversible where possible.

### Maintainable

It remains understandable to engineers who did not build the first version.

A system that cannot be understood cannot be safely changed. A system that cannot be safely changed will eventually fall behind and require replacement.

### Configurable

It supports low-effort implementation of form-based workflows, validation rules, business rules, decisions, and notifications.

Configuration should make business variation cheaper. It should not make unsafe behaviour easier.

### Portable

It is not trapped in one hosting environment, one cloud provider, or one vendor's product suite.

The same application architecture should work on a laptop, in an on-premises data centre, or in a public cloud.

### Operable

It can be monitored, alerted, diagnosed, audited, restored, and rebuilt.

Operational concerns such as telemetry, backup, restore, secrets, certificates, migrations, deployment, and disaster recovery are part of the architecture.

### Economical to evolve

It does not become more expensive to change every year.

The architecture should keep future change affordable by separating concerns, isolating dependencies, and making replacement possible.

---

## Architectural principles

The projects in this repository explore architectural principles that protect those institutional values.

### 1. Build security into the design

Secure-by-design software makes unsafe behaviour harder to express and easier to detect.

This means using strong domain models, validated inputs, safe error handling, explicit trust boundaries, secure sessions, least privilege, fine-grained authorisation, audited commands, secret management, zero trust, and defence in depth.

### 2. Make change explicit and versioned

Long-lived systems change constantly.

APIs, events, forms, workflows, business rules, integration contracts, and projections should evolve through explicit versioning and migration paths.

Old data must remain readable. Old decisions must remain explainable.

### 3. Prefer understandable modules over distributed complexity

The default architecture is a modular monolith.

Bounded contexts provide strategic separation. Vertical slices keep features understandable. Aggregates protect consistency. Ports and adapters isolate domain logic from infrastructure.

The aim is simple: one feature should be understandable end to end.

### 4. Treat products as adapters

The system depends on architectural roles, not permanent product choices.

Identity, data store, cache, message broker, object storage, secrets, certificates, workflow, rules, observability, ingress, email, and payments are platform capabilities.

Specific tools are adapters.

### 5. Keep configuration inside safe boundaries

Forms, workflows, validation rules, decision tables, and notifications should be configurable and versioned.

But configuration must not bypass the domain model, authorisation, auditability, or security controls.

Configuration is for controlled business variation. Code still protects correctness.

### 6. Design for operational reality

Production readiness includes more than application code.

Logs, metrics, traces, alerts, audit events, health checks, backups, restore tests, migrations, secret rotation, certificate rotation, image scanning, image signing, rollback, and disaster recovery are architectural concerns.

### 7. Run anywhere by design

Every project should run locally first.

The same architecture should then be deployable to Docker Compose, Kubernetes, on-premises Rancher, Hetzner, Azure, or AWS through environment-specific configuration and adapters.

No environment is the only real one.

### 8. Express intent in every API call

All business API operations are named after what they do, not what they act on.

Commands and queries are both expressed as `POST` with a JSON body. The URL is the operation name. There are no `GET`, `PUT`, `PATCH`, or `DELETE` business endpoints. Operational and documentation endpoints such as health checks and OpenAPI documents remain conventional `GET` endpoints.

This is a deliberate departure from REST. It makes every operation explicitly nameable, consistently authenticated, uniformly auditable, and free from the semantic assumptions HTTP verbs carry. The intent of every call is visible in its URL.

---

## Quality attributes

**es-lab** focuses on architectural qualities that matter for long-lived institutional software.

Each project should make one or more of these qualities visible and testable.

| Quality attribute | What it means here                                                                                  |
| ----------------- | --------------------------------------------------------------------------------------------------- |
| Security          | Unsafe behaviour should be hard to express, easy to detect, and contained by defence in depth.      |
| Changeability     | Forms, workflows, rules, APIs, events, and integrations should evolve through explicit versioning.  |
| Maintainability   | A feature should be understandable end to end without requiring knowledge of the entire system.     |
| Configurability   | Business variation should be achievable through safe, versioned configuration where appropriate.    |
| Portability       | The same application architecture should run on laptop, on-premises, and public cloud environments. |
| Operability       | The system should be observable, diagnosable, recoverable, and safe to operate.                     |
| Resilience        | The system should tolerate failure, support recovery, and avoid single points of catastrophic loss. |
| Performance       | The architecture should support predictable response times and scale where needed.                  |
| Auditability      | Important commands, decisions, events, and configuration changes should be explainable later.       |
| Evolvability      | Core technology choices should be replaceable without rewriting the domain model.                   |

These are not abstract aspirations. Each project should define lightweight fitness criteria that show which architectural claims it proves.

For example:

```text
A new form field can be added by changing configuration, not backend code.
A historical submission renders using the form version active at submission time.
A protected API endpoint rejects unauthenticated requests.
A command creates an audit event.
The app runs with docker compose up.
```

Production-grade projects may define measurable targets such as latency, throughput, availability, RTO, RPO, deployment frequency, or restore success criteria where those targets are relevant.

---

## What this repository demonstrates

**es-lab** demonstrates a path toward a secure, configurable, portable full-stack reference architecture.

The long-term target includes:

* web frontends
* application backend
* intentful business API design using named POST commands and queries
* modular monolith architecture
* bounded contexts
* vertical slices
* event-sourced state
* read-model projections
* caching
* asynchronous messaging
* object storage for files and documents
* federated identity
* browser-token-free session authentication
* authenticated service-to-service communication
* fine-grained authorisation
* secrets, certificates, and key management
* configurable forms
* configurable workflows
* configurable business rules
* server-pushed live updates
* payment processing
* email notifications
* observability
* reproducible infrastructure provisioning
* automated build, test, and delivery
* cloud and on-prem deployment patterns

The tools are not the point.

The architectural roles are the point.

Each project should make clear:

* what problem is being solved
* what architectural role the technology plays
* what trade-offs are introduced
* what can be replaced later
* what would need to change for production

---

## Repository style

Each project is a complete, isolated, runnable application.

There is deliberately no shared application code, no shared build system, no shared dependency graph, and no shared Docker Compose file between projects.

The only shared files at the repository root are:

```text
README.md
ROADMAP.md
LICENSE
.gitignore
```

A typical project folder looks like this:

```text
01-example-project/
├── README.md
├── docker-compose.yml
├── frontend/          # Angular
├── backend/           # Clojure
├── db/                # migrations, seed data, setup
├── infra/             # project-specific infrastructure config
└── docs/              # architecture notes, diagrams, runbook
```

This isolation is intentional.

Each project should be easy to run, understand, copy, break, improve, and show as a working example.

Duplication is acceptable when it keeps a project understandable.

---

## Project README standard

Every project should explain itself using the same basic structure:

```markdown
# Project name

## What this demonstrates

## Architecture

## How to run it

## What to look at

## Quality attributes demonstrated

## Fitness criteria

## Security design notes

## Configuration and evolution notes

## Production notes

## What is not proven yet

## What I learned
```

Each project should prove one or more architectural claims through working software.

The `Fitness criteria` section should make those claims concrete and testable.

---

## Security design notes

Every project should include security thinking, even when security is not the main focus.

Each project should answer:

```markdown
## Security design notes

### What could go wrong?

### What design choices reduce the risk?

### What is deliberately not solved yet?

### How would this be hardened in production?
```

Security belongs in the design conversation from the beginning.

---

## Configuration and evolution notes

Because configurable forms, workflows, and business rules are central to the goal, relevant projects should answer:

```markdown
## Configuration and evolution notes

### What is hardcoded in this project?

### What has become configurable?

### What must remain protected by code?

### How is configuration versioned?

### How are historical submissions, workflows, or decisions preserved?
```

Configuration is for controlled business variation. Code still protects correctness, security, and invariants.

---

## Direction of travel

The repository grows through small, working projects.

A possible journey is:

```text
basic frontend + backend
        ↓
persistence + task-based API design
        ↓
secure domain modelling
        ↓
configurable forms
        ↓
configurable validation and rules
        ↓
workflow
        ↓
identity and sessions
        ↓
fine-grained authorisation
        ↓
files and object storage
        ↓
async messaging
        ↓
email and payments
        ↓
observability
        ↓
secrets and certificates
        ↓
Docker Compose reference platform
        ↓
Kubernetes and Helm
        ↓
CI/CD and supply-chain security
        ↓
GitOps
        ↓
cloud and on-prem deployment adapters
        ↓
secure configurable portable reference application
```

The destination is not a giant demo.

The destination is a set of proven, understandable, reusable patterns for building institutional software that can endure.

---

## Working definition

**Enduring software** is software that continues to meet the needs of an institution over time.

It remains secure, understandable, adaptable, operable, portable, and economical to change long after the first version has gone live.

---

## Acknowledgements

This work is influenced by ideas from:

* [*Domain-Driven Design* - Eric Evans](https://www.dddcommunity.org/book/evans_2003/)
* [*Implementing Domain-Driven Design* - Vaughn Vernon](https://www.dddcommunity.org/book/implementing-domain-driven-design-by-vaughn-vernon/)
* [*Secure by Design* - Dan Bergh Johnsson, Daniel Deogun, Daniel Sawano](https://www.manning.com/books/secure-by-design)
* [The Reactive Manifesto](https://www.reactivemanifesto.org/)
* [The Twelve-Factor App](https://12factor.net/)
* [Clojure's emphasis on simple, coherent, composable systems](https://clojure.org/)
