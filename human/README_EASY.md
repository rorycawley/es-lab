# Registry System — The Essentials

*Ten minutes. This is what you need before the Guiding Specification is worth reading, and it is enough to work from on day one.*

---

## The one sentence

**We record what the registry did.** Everything anyone ever sees — the register, a search result, a certificate — is computed from that record, and can be thrown away and rebuilt.

---

## Why this is not ordinary software

Three facts about the domain. Between them they force almost every decision that follows.

**The product is evidence, not data.** Priority disputes, rectification, and compensation for people who relied on the register all turn on proving what we asserted at a past moment. A registry that overwrites has destroyed the evidence of its own guarantee.

**The law already works this way.** Registries have recorded instruments and derived a register from them since the 1850s. We are not imposing a pattern on the domain — CRUD would be the imposition.

**It has to last decades.** The record will outlive this codebase, this team, and almost certainly this database.

---

## The five things that must be right from day one

Not "expensive to change later" — **impossible**, because the information was never captured. Every one of these must be true of the very first act written in production.

| # | Every act must carry | If it doesn't, we permanently lose |
|---|---|---|
| 1 | **Two times** — when it became true, and when we recorded it | The ability to tell "the owner changed" from "we had the owner wrong". And the ability to say what the register said when someone searched it |
| 2 | **Authority** — statutory power, who acted, their delegation, at whose instance, and on what evidence | The ability to show we were *entitled* to change the register, not merely that we did |
| 3 | **The rule regime version** in force at the time | The meaning of the act. In ten years, "what did this actually do?" becomes unanswerable |
| 4 | **The decision reached**, not just the inputs | Correctness of history. Rebuild in 2035 and today's rules silently re-decide 2019's judgements |
| 5 | **Its place in the order**, recorded not computed | Priority. Which is the most legally consequential thing the system calculates |

**And none of them is ever overwritten.** Corrections are new acts, not edits.

---

## The things you can get wrong

Genuine relief, and worth knowing so you don't agonise in the wrong places.

Projections and read models. Which act types exist. Query performance. Screens and APIs. Database layout. Almost every architectural choice.

All of it is derived, disposable, or additive. **You can delete every projection in the system and rebuild it from the acts.**

> **The asymmetry that should guide you:** getting an act wrong is unrecoverable. Getting a projection wrong is nearly free. Spend your thinking accordingly.

---

## The one test

For anything you are about to record:

> **Could this be read aloud in court as an account of what the registry did, and why it was entitled to do it?**

If yes, it is an act. If it reads as a description of a data change, you have modelled the mechanism and lost the meaning.

The filter is authority: **no statutory power exercised, no act.** A registration is an act. A refusal is an act — the registrar exercised a power and said no. A sent notification is not.

---

## Not everything we store is an act

Acts are one store among five. Mixing them destroys the property that makes the act record evidence.

| Store | Holds | Kept |
|---|---|---|
| **Act record** | Acts, and only acts | Forever |
| **Assertion ledger** | What we actually told someone — searches, certificates | Statutory period |
| **Document archive** | Instruments and evidence, referenced and hashed by acts | Statutory period |
| **Audit store** | Who read what, who was granted access, admin actions | Bounded — has a lawful *maximum* |
| **Operational** | Commands, workflow, telemetry, rejected junk | Short; prune it |

One worked case produced seventeen stored records. **Three were acts.**

---

## The five traps

**Replaying history under today's rules.** The silent one. Reconstruction must evaluate *no* rules — it reads decisions already recorded. This is why the decision goes on the act.

**One events table with a type column.** Tempting with a single database. It breaks reconstruction, collides four retention regimes, and puts the audit trail within reach of the people it records.

**Treating a projection as the truth.** The moment someone corrects data by editing a projection, the model is gone and nobody will notice for months.

**Modelling field changes instead of legal acts.** `OwnerNameChanged` fails the authority test — there is no statutory power to change a name in isolation. The act was a registration of transfer.

**Proving the model on the easy case.** Registration works in any architecture. If your first vertical slice doesn't include a **rectification**, you have proved nothing.

---

## What to do first

**Get three questions answered by counsel.** Is registration constitutive or declaratory? Is compensation our function? What term does our statute use for what we're calling an "act"? The first can invalidate parts of the model, not merely refine it.

**Fill in the thirteen questions for one act type, on paper, before writing code.** The ones you cannot answer are real gaps in domain understanding, and finding them now rather than in year three is the entire point.

**Build one vertical slice, complete, including a rectification.** Command through to a historical query on both time axes, plus a replay. Not three act types with the interesting parts stubbed.

---

## Where to go next

| Document | For |
|---|---|
| **Guiding Specification** | The full commitments, the thirteen questions, quality attributes, capacity |
| **Worked Examples** | The rules operating on a real case, with act payloads. Start here if the specification feels abstract |
| **Decision Record Register** | Mechanism — what we chose, why, and which commitment each choice serves |

---

## If you remember only one thing

The register is not a database of current facts with an audit trail bolted on.

**It is a permanent record of what the registry did, from which the current position happens to be computable.**

Everything else in these documents is a consequence of taking that seriously.