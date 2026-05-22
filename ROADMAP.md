# Roadmap

This document describes the current direction of travel for **es-lab**: the architectural roles being explored, the specific tools chosen as today's adapters for those roles, and the sequence of mini-projects through which the architecture is being proved out.

The principles and values that motivate this work live in [`README.md`](./README.md). This document is about the *current* expression of those principles. This document will change more often than the principles.

**This is not a delivery commitment or a fixed backlog.** It is a map of architectural questions to be explored through working examples. Projects will be added, dropped, deferred, or reshaped as ideas are proved or refined.

---

## How to read this document

Every entry below names an **architectural role** first and an **implementation choice** second.

The role is what matters. The implementation is today's answer, chosen because it is open source, self-hostable, substitutable, and well-documented, and may be replaced over time.

If a tool listed here is later replaced by a better one, the architecture does not change. Only the adapter changes (within limits described in [Substitution levels](#substitution-levels) below).

### How the project sequence is ordered

The phases move from simple to complex, but a few ordering choices are deliberate and worth understanding:

- **Evolution and versioning patterns appear as early as the things they evolve.** Aggregate upcasting and event schema versioning sit in Phase 1, immediately after event sourcing is introduced. Integration event contracts sit immediately after RabbitMQ. Workflow, form, and rule evolution sit immediately after the workflow engine. These patterns are bedrock, not afterthought. Retrofitting them later means rewriting history.

- **SSE is in Phase 2, not later.** Server-sent events through edge protection is a known architectural risk (edge providers can interfere with streamed responses). Proving it works early is much cheaper than discovering it does not after thirty projects have been built around the assumption.

- **The API gateway is introduced relatively late (Phase 6).** Earlier projects expose backends directly so that auth, rate limiting, and webhook signature validation can be understood in isolation before being centralised. Introducing the gateway too early would hide the concerns it is meant to manage.

- **Edge protection is introduced even later.** The gateway is a useful unit of work on its own; adding edge protection beforehand would conflate two distinct concerns (origin protection and ingress policy) into one project.

- **Production-grade deployment (Phase 7) comes before cloud-agnostic deployment (Phase 8).** Kubernetes, supply chain security, GitOps, and HA databases are proved on a single environment first. Multi-environment substitution is the final test, not the starting point.

---

## First milestone

The first milestone is to prove the smallest useful vertical slice:

- one frontend
- one backend
- one database
- one task-based use case
- one simple user-context or session pattern
- one audit trail
- one Docker Compose setup

This is not the full architecture. It is the first proof that the architecture can be built incrementally, end to end, on a laptop.

The first milestone uses **one** frontend. The target architecture later expands this into separate customer and backoffice portals (introduced in project 05). Likewise, real OIDC BFF PKCE authentication arrives in Phase 3; the first milestone is satisfied by any user-context or session mechanism sufficient to scope the task to a user and produce an audit trail.

Everything else in this roadmap builds outward from that proof.

---

## Substitution levels

Not every replacement has the same cost. The roadmap distinguishes four levels:

| Level | Meaning | Example |
|---|---|---|
| Level 1 | Configuration change | Switching object storage endpoints from local Minio to AWS S3. |
| Level 2 | Adapter change | Replacing the SendGrid client with an SES client behind the same outbound-email port. |
| Level 3 | Contract or workflow change | Replacing RabbitMQ with Azure Service Bus; message semantics differ enough to require revisiting consumer behaviour. |
| Level 4 | Data migration or architectural change | Replacing the Postgres event store with a different event store; existing events must be migrated and replay paths re-validated. |

The goal is **not** to make every substitution free. The goal is to keep substitutions away from core domain code where possible, and to know in advance which substitutions are cheap and which are expensive.

---

## Target architecture

### Application shape

- **Two Single Page Application UIs** sharing one backend:
  - A **customer portal** for public users, authenticated via a federated public identity provider.
  - A **backoffice portal** for employees, authenticated via the local identity provider with MFA, reachable only from the corporate network.
- A **modular monolith backend**, organised by DDD bounded contexts. Each context is internally structured as vertical slices around aggregates, with ports and adapters isolating the domain from infrastructure.
- An **event-sourced core**, where domain events are the sole source of truth. CQRS projections build the read store. A transactional outbox publishes integration events between bounded contexts. Consumers deduplicate via an inbox. Aggregates use optimistic concurrency.
- **Multiple backend instances** behind an API gateway for scalability and resilience. All instances are stateless, all act as competing consumers on background-job queues. State needed for sessions, subscriptions, coordination, or background processing lives outside the application instances, in Redis, Postgres, or RabbitMQ as appropriate.
- **Live updates** to the SPA via server-sent events.

### Defence in depth

Each layer assumes the previous layer may be compromised:

```
Internet                                Corporate network
   │                                            │
   ▼                                            │
Edge protection                                 │
  • WAF, OWASP rules                            │
  • DDoS protection                             │
  • Bot controls                                │
  • Rate limiting                               │
  • Security headers                            │
  • Outbound-only tunnel from origin            │
  • mTLS-style origin proof                     │
   │                                            │
   ▼                                            ▼
On-prem firewall                       Split-horizon DNS
  • Allows only edge IPs                  • Backoffice host
                                            not in public DNS
   │                                            │
   └─────────────┬──────────────────────────────┘
                 ▼
        Load balancer HA pair, stable VIP
                 │
                 ▼
        API gateway HA pair, three listeners:
          • Public
          • Backoffice
          • Internal
          plus JWT validation, webhook signature validation,
          route-level rate limiting
                 │
                 ▼
        Modular monolith
          • OIDC BFF PKCE (tokens server-side, httpOnly cookie)
          • CSRF protection (double-submit token)
          • Idle and absolute session timeouts
          • Signed upstream identity header validation
          • Fine-grained authorisation
          • Backend rate limiting
          • Domain security rules
          • Audit logging
```

### API surface segregation

Endpoints are grouped into three reachability tiers, enforced at the gateway (separate listeners) and verified at the backend (signed upstream identity header from the gateway):

| Group | Reachable from | Examples |
|---|---|---|
| **Public** | Internet, via edge protection to public gateway listener | Login, browse catalogue, view own data |
| **Backoffice** | Corp network only, via load balancer to backoffice gateway listener | Approve refunds, edit pricing, view audit logs |
| **Internal** | Cluster only, no external route | Webhook processors, async job triggers, admin endpoints |

Reachability is enforced *in addition to* authorisation. Permission misconfiguration cannot expose what is also unreachable.

### Encryption

- **In transit:** TLS at every hop. Internal service-to-service traffic uses mTLS with short-lived certificates issued by an internal PKI and rotated automatically.
- **At rest:** Encrypted persistent volumes for databases and caches; server-side encryption with a KMS-managed key for object storage; etcd at-rest encryption; secrets injected at runtime from a central audited store, never stored in Git or ConfigMaps.

### Erasure of personal data

Personal data that must support erasure can be encrypted with subject-scoped keys. Destroying a subject key can make selected data unreadable without rewriting the append-only event store.

This pattern is architecturally useful but not a complete solution on its own. It must be validated against audit, legal retention, backups, projections, logs, and reporting requirements. The architectural pattern is a starting point; the legal and operational obligations sit on top of it.

### Disaster recovery

The application tier is stateless. Infrastructure is defined entirely as code. Data continuity is provided by HA database clusters, replicated object storage, and point-in-time backups. A new datacenter can be brought up by applying the IaC and restoring backups, with a measurable, documented RTO and RPO.

---

## Architectural roles and current implementation choices

Each row is a **role**. The implementation column shows today's choice for the laptop / on-prem / Hetzner environment, and the substitutes proven (or planned) for AWS and Azure.

**Cloud substitutions are not assumed to be behaviourally identical.** Each substitution must preserve the architectural role and satisfy the project fitness criteria, even if implementation details, semantics, or operational characteristics differ. See [Substitution levels](#substitution-levels) for the framework used to reason about the cost of each substitution.

| Role | Laptop / On-prem / Hetzner | AWS | Azure |
|---|---|---|---|
| Edge protection (WAF, DDoS, headers) | Cloudflare | Cloudflare | Cloudflare |
| Origin protection (prevent bypass) | Cloudflare Tunnel plus Authenticated Origin Pulls | Cloudflare Tunnel plus Security Groups (edge IPs only) | Cloudflare Tunnel plus NSG (edge IPs only) |
| Network firewall | OPNsense | AWS Network Firewall / Security Groups | Azure Firewall / NSG |
| Load balancer (L4, VIP) | nginx HA pair (keepalived/VRRP) | AWS NLB | Azure LB |
| Private DNS (split-horizon) | OPNsense DNS / bind | Route 53 Private Zone | Azure Private DNS Zone |
| Corporate VPN | OPNsense WireGuard | AWS Client VPN | Azure VPN Gateway |
| API gateway | Apache APISIX HA pair | AWS API Gateway | Azure API Management |
| API surface segregation | APISIX three-listener pattern plus signed identity headers | Same pattern | Same pattern |
| Identity provider and broker | Keycloak | AWS Cognito | Azure Entra ID |
| Customer portal UI | Angular plus Google OIDC via Keycloak broker | Angular plus Cognito | Angular plus Entra B2C |
| Backoffice portal UI | Angular plus Keycloak local realm (MFA) | Same | Same |
| Backend web service | Clojure | Clojure on EKS | Clojure on AKS |
| Background jobs | Clojure (in-process via RabbitMQ) | Clojure on EKS | Clojure on AKS |
| Database migrations | Migratus (Clojure, as Kubernetes Job) | Same | Same |
| Message broker | RabbitMQ | Amazon MQ / SQS | Azure Service Bus |
| Event sourcing write and read store | Postgres (Patroni HA) | AWS RDS Postgres | Azure Database for Postgres |
| Backup and PITR | pgBackRest to object storage | RDS automated backups | Azure Backup |
| Cache and session store | Redis cluster | AWS ElastiCache | Azure Cache for Redis |
| Secrets management (incl. KMS, PKI) | OpenBao | AWS Secrets Manager plus KMS plus ACM PCA | Azure Key Vault |
| Certificate management | cert-manager plus OpenBao PKI | cert-manager plus ACM | cert-manager plus Key Vault |
| Configuration (non-sensitive) | Kubernetes ConfigMaps | Same | Same |
| Event schema management | Schema-in-Git with discipline | Same | Same |
| API documentation | OpenAPI generated from Clojure handlers | Same | Same |
| Fine-grained authorisation | OpenFGA | OpenFGA on EKS | OpenFGA on AKS |
| Object storage | Minio (with site replication) | AWS S3 | Azure Blob Storage |
| Malware scanner | ClamAV | AWS GuardDuty Malware Protection | Microsoft Defender for Storage |
| Workflow and configurable forms | Flowable | Flowable on EKS | Flowable on AKS |
| Observability (logs, metrics, traces) | Grafana LGTM | CloudWatch plus Grafana | Azure Monitor plus Grafana |
| UI telemetry (RUM) | Grafana Faro | Same | Same |
| Supply chain security | Cosign plus Trivy plus Kyverno | Same | Same |
| Time synchronisation | chrony | chrony / AWS Time Sync | chrony / Azure Time Sync |
| Container orchestration | Kubernetes plus Helm (Rancher) | AWS EKS | Azure AKS |
| GitOps and CI/CD | GitHub Actions plus ArgoCD | Same | Same |
| Infrastructure as code | OpenTofu (Hetzner) | OpenTofu / AWS CDK | OpenTofu / Bicep |

---

## Project sequence

Each project is a complete, isolated, runnable application. Project numbering reflects the order in which architectural ideas are introduced; the ideas build on each other, but each project still stands alone.

The roadmap names the architectural claim each project proves. The detailed fitness criteria, runbook, and architectural notes belong in the individual project's own `README.md`.

**Status legend:**

- `planned` — not yet started
- `in progress` — actively being built
- `complete` — built and runnable
- `rework needed` — built but requires revision
- `deferred` — intentionally postponed or no longer part of the current path

### Phase 1 — Foundations (laptop, Docker Compose)

| # | Status | Project | Proves out |
|---|---|---|---|
| 01 | planned | `hello-backend` | A minimal Clojure backend serves a single endpoint and can be run with `docker compose up`. |
| 02 | planned | `hello-frontend` | A minimal Angular SPA renders a single page and can be run with `docker compose up`. |
| 03 | planned | `frontend-calls-backend` | The SPA calls a backend endpoint and renders the result. End-to-end toolchain proven. |
| 04 | planned | `postgres-persistence` | The backend reads and writes Postgres via a clean adapter; data survives container restart. |
| 05 | planned | `dual-portal-basics` | Two SPAs (customer and backoffice) call distinct backend endpoints from the same backend. |
| 06 | planned | `ports-and-adapters` | Domain logic is decoupled from infrastructure; swapping the persistence implementation changes only adapters. |
| 07 | planned | `modular-monolith` | Multiple bounded contexts live in one deployable while remaining strictly isolated. |
| 08 | planned | `event-sourcing-basics` | Domain events are the source of truth; state is derivable by replay; optimistic concurrency prevents lost updates. |
| 09 | planned | `aggregate-evolution-upcasting` | Old events still replay correctly when aggregates evolve, via upcasters. History is never rewritten. |
| 10 | planned | `event-schema-versioning` | Additive event changes are backward-compatible; breaking changes produce a new event type while old versions remain valid forever. |
| 11 | planned | `cqrs-projections` | Read and write models evolve independently. The read store can always be rebuilt from the event store. |
| 12 | planned | `read-model-rebuild` | A projection can be retired, replaced, or rebuilt by replaying the event store. |
| 13 | planned | `vertical-slice-architecture` | Within a bounded context, organising by use case is more maintainable than organising by technical layer. |

### Phase 2 — Reliability and messaging

| # | Status | Project | Proves out |
|---|---|---|---|
| 14 | planned | `redis-cache-and-sessions` | Redis used safely for both ephemeral caching and durable-enough session storage. |
| 15 | planned | `rabbitmq-integration-events` | Bounded contexts communicate asynchronously via integration events; multiple instances compete safely on the same queue. |
| 16 | planned | `integration-event-contracts` | Cross-module event contracts are explicit and versioned in Git; producers can publish multiple versions during transition. |
| 17 | planned | `transactional-outbox-inbox` | Events persisted and published atomically (outbox); consumers handle duplicates safely (inbox). |
| 18 | planned | `database-migrations` | Schema changes ship as a separate, idempotent, repeatable artifact independent of the app. |
| 19 | planned | `realtime-sse` | SSE works end-to-end from Clojure to Angular, including through edge protection. *(Risk-first: proving early avoids architectural surprise.)* |

### Phase 3 — Identity, auth, and security

| # | Status | Project | Proves out |
|---|---|---|---|
| 20 | planned | `dual-bff-oidc-pkce` | Customer portal authenticates via broker-federated public OIDC; backoffice portal authenticates via local realm. Two distinct sessions, two distinct cookies, both BFF-PKCE. |
| 21 | planned | `csrf-and-sessions` | Cookie auth is CSRF-safe via double-submit tokens; sessions enforce idle and absolute timeouts independent of JWT lifetime. |
| 22 | planned | `google-signin-federation` | The identity broker can add or replace external IdPs without changing the application. |
| 23 | planned | `m2m-oauth` | Service-to-service auth uses the same identity infrastructure; no shared secrets in code. |
| 24 | planned | `openfga-fine-grained-authz` | Authorisation decisions externalised into a relationship graph supporting per-resource rules. |
| 25 | planned | `openbao-secrets` | No secrets in Git, config, or images; injected at runtime from a central audited store. |
| 26 | planned | `cert-manager-mtls` | Every internal connection is mutually authenticated, encrypted, and rotated automatically. |

### Phase 4 — Storage, files, and workflow

| # | Status | Project | Proves out |
|---|---|---|---|
| 27 | planned | `minio-object-storage` | Large files handled via pre-signed URLs, encrypted at rest with a KMS-managed key. |
| 28 | planned | `clamav-malware-scan` | Uploaded files are quarantined and scanned asynchronously; no malicious content reaches consumers. |
| 29 | planned | `gdpr-crypto-shredding` | Subject-scoped key destruction renders selected data unreadable without rewriting the event store. Must be validated against audit, backup, and retention requirements. |
| 30 | planned | `flowable-workflow` | Business analysts can define forms and workflows; Angular renders forms dynamically without backend code changes. |
| 31 | planned | `workflow-evolution` | In-flight workflow instances continue on their original BPMN version; new instances use the new version. |
| 32 | planned | `forms-evolution` | Forms can add, rename, or remove fields without breaking historical submissions. |
| 33 | planned | `business-rules-evolution` | Decision tables are versioned; historical decisions can always be re-explained. |

### Phase 5 — Integrations

| # | Status | Project | Proves out |
|---|---|---|---|
| 34 | planned | `sendgrid-email` | Outbound third-party calls follow a consistent connector pattern with retries, timeouts, and credentials from the secrets manager. |
| 35 | planned | `stripe-payments` | Inbound webhooks are cryptographically verified at the edge, deduplicated, processed reliably via the inbox pattern. |
| 36 | planned | `docusign-envelopes` | Long-running external interactions orchestrated by the workflow engine; webhook callbacks resume workflow state. |
| 37 | planned | `api-first-openapi` | Public API documented, versioned, and consumable by third parties; generated from code. |
| 38 | planned | `api-versioning` | Public APIs evolve via URL versioning and deprecation headers; old versions persist until usage drops to zero. |

### Phase 6 — Edge, gateway, and observability

| # | Status | Project | Proves out |
|---|---|---|---|
| 39 | planned | `apisix-gateway` | Single ingress enforces JWT validation, rate limiting, and webhook signature checking. *(Introduced after the concerns it manages are understood in isolation.)* |
| 40 | planned | `api-surface-segregation` | Public, backoffice, and internal endpoints isolated at the gateway and verified at the backend. |
| 41 | planned | `nginx-ha-load-balancer` | A load balancer HA pair provides a stable VIP in front of stateless gateway instances. |
| 42 | planned | `cloudflare-edge` | Origin has no publicly reachable IP; all traffic flows through an outbound tunnel with WAF, DDoS protection, and security headers. |
| 43 | planned | `grafana-lgtm-observability` | Logs, metrics, and traces from every component flow into a single pane of glass. |
| 44 | planned | `grafana-faro-ui-telemetry` | Frontend errors, performance, and user journeys observable alongside backend telemetry. |

### Phase 7 — Production-grade deployment

| # | Status | Project | Proves out |
|---|---|---|---|
| 45 | planned | `kubernetes-helm` | The whole stack runs on Kubernetes locally with the same manifests that will run in production. |
| 46 | planned | `network-policies-and-psa` | Compromised pods cannot reach pods they shouldn't, run as root, or escalate privileges. |
| 47 | planned | `supply-chain-security` | Only signed, scanned images deploy; admission control blocks anything not built by the pipeline. |
| 48 | planned | `github-actions-cicd` | Every commit produces a reproducible, signed, scanned artifact. |
| 49 | planned | `argocd-gitops` | Deployed state matches Git; drift is detected and corrected; rollback is a `git revert`. |
| 50 | planned | `postgres-patroni-ha` | Database survives node failure with automatic failover; writes to primary, reads correctly. |
| 51 | planned | `pgbackrest-pitr` | Database can be restored to any point in time from object storage backups; restore has been tested, not just configured. |

### Phase 8 — Cloud-agnostic deployment

| # | Status | Project | Proves out |
|---|---|---|---|
| 52 | planned | `opentofu-hetzner` | Entire infrastructure (network, Kubernetes, DNS, storage) reproducible from code. |
| 53 | planned | `opnsense-and-cloudflare-tunnel` | On-prem network refuses any traffic not from the edge protection; even a discovered origin IP cannot be reached. |
| 54 | planned | `private-backoffice-routing` | Backoffice traffic never traverses the internet; split-horizon DNS, corp VPN, and private subnets. |
| 55 | planned | `disaster-recovery-drill` | Complete datacenter loss recovered in a measurable, documented RTO and RPO using only Git, backups, and IaC. |
| 56 | planned | `public-cloud-deployment-substitution` | The "runs anywhere" claim holds: the same application deployed to a public cloud (AWS or Azure) with managed services substituted, no domain code changes. The choice of cloud is itself part of the substitution exercise. |

---

## Status

This roadmap is aspirational. Project numbering reflects intended sequence; not all projects exist yet.

Each project, when built, will include its own `README.md` following the project README standard described in the repository [`README.md`](./README.md), with explicit fitness criteria showing which architectural claims it proves.

The roadmap will evolve as ideas are proved, refined, or replaced. The principles in the repository `README.md` should change much less often.
