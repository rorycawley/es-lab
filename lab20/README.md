# Lab 20: outbox and inbox

[Lab 12](../lab12) made the transactional-outbox argument against an in-memory vector — a setting where a transaction is free and the failure the pattern exists to prevent **cannot occur**. It was a thought experiment.

[Lab 19](../lab19) brought real transactions. This lab spends them.

Two modules, one Postgres. A truck module that records sales, and a customer app and a purchasing module that need telling.

## One command, one transaction

```clojure
(jdbc/with-transaction [tx ds]
  (let [events (store/append tx …)]
    (ledger/record! tx command (count events))
    (doseq [message (announce event)]
      (outbox/enqueue! tx message))))
```

The events, the ledger row and the outgoing messages all commit together. That is the whole of the outbox pattern — not *write the fact, then publish it*, but **one write** containing everything the outside world will later learn.

A test kills a command mid-flight and checks there is no partial state: no events, no ledger row, no outbox row.

## Where lab 12's qualification earns its keep

Lab 12 said exactly-once *delivery* is not achievable **across a network** — and was careful to say "across a network". This is the lab that shows why that qualification was doing work.

Across a network, at-least-once. **Across a schema, exactly once.**

### Across a boundary: at-least-once, unavoidably

The relay publishes, then marks the row sent. Two writes to two systems, no transaction spanning them:

```clojure
(relay-across-a-boundary! ds publish! crash-after-publish?)
;; published: 1 message.  outbox pending: 2 — nothing was marked
;; restart →  3 deliveries of 2 distinct messages
```

Reverse the order and you trade a duplicate for a loss: mark first, crash, and nothing will ever retry it. A test pins that too. **There is no safe ordering — only a choice of which failure you prefer**, and publish-then-mark is chosen because a duplicate is recoverable and a loss is not.

### Inside one database: exactly once

The outbox row and the inbox row are two rows in one schema. One transaction covers both, and there is no window to crash in:

```clojure
(relay-within-one-database! ds {:customer-app record-notification!})
;; => ([msg-1 :handled] [msg-2 :handled])
```

A test makes the effect throw, and everything rolls back together — nothing marked sent, nothing in the inbox, no notification. Then a later run delivers **exactly once**.

That is a real capability a distributed system does not have, and it is the strongest argument for a modular monolith I know that isn't hand-waving. The cost is equally real: this only works while the modules share a database, which is exactly the coupling you gave up when you split them.

## The inbox, and what lab 12's consumer could not do

Lab 12's consumer deduplicated with a `:seen` set inside its read model. That works — for one reason: **the effect *was* the read model**, so the dedupe record and the effect were the same write.

Move the effect anywhere else and the `:seen` set protects nothing. So:

```clojure
(inbox/handle-once! ds :customer-app fact-id
                    (fn [tx] (record-notification! tx row)))
```

The inbox row and whatever the effect writes share one transaction. There is no state in which the effect happened and the system has forgotten.

The test delivers the same message three times and counts **one** notification — where the notification is written to a different table entirely, which is the case lab 12 could not have covered.

Two details the schema had to get right, and one of them I got wrong first:

- **Keyed by the fact, not the delivery.** A republished message arrives in a new envelope, so deduplicating on `:message/id` lets it through ([lab4](../lab4)).
- **Keyed by recipient *and* fact.** One fact goes to several modules and each must handle it once. My first schema made `fact_id` the primary key; the test for two recipients found it immediately.

## The hole in lab 10

Lab 10 deduplicated a command by asking whether any event carried its causation id:

```clojure
(if (store/caused-by? log (:command/id command)) …)
```

The archive's [ADR-0004](../archive/04a-legal-facts/docs/adrs/0004-command-ledger-idempotency.md) says why that isn't enough:

> `causation_id` and `correlation_id` are traceability metadata. They are not global uniqueness constraints for event identity… Command idempotency is enforced by a command ledger keyed by command ID.

And [lab5](../lab5) supplies the case that breaks it: **a command may legitimately produce no events.** Load zero cones onto a truck and nothing is recorded — so no event carries the causation id, `caused-by?` answers false forever, and the command runs again on every pass.

```clojure
(go! ds (command :load-truck {:flavour "vanilla" :quantity 0}))
;; => []            no fact, correctly
;; events with this causation-id: 0
;; ledger entry:    {:event-count 0}   ← the ledger saw it anyway
(go! ds same-command)
;; => :already-handled
```

Lab 10's policy never hits this, because its restock quantity is always positive. The bug is latent, not live — which is the more dangerous kind.

A ledger row is written whether the command produced three events, one, or none.

## Serialisation, one level up

[Lab 19](../lab19) found that JSON has no keyword type, and the repository's answer was to stop writing keywords into streams. Writing this lab found the same tax one level up, on **keys**, where declining it is not an option: `json/write-str` drops namespaces, so `:event/id` serialises to `"id"` and comes back meaning something else entirely.

The fix is not a coercion this time — it's a naming decision. The payload is a **contract**, read by modules that may not be Clojure, and a contract has no business carrying Clojure namespaces. So the wire key is `:fact-id`, and the translation from `:event/id` happens where every other contract decision does: in the one file that owns it ([lab12](../lab12)).

## What is recapped rather than re-argued

At-least-once delivery, deduplicating on the fact's id, and the event-to-message translation are all [lab12](../lab12)'s. This lab assumes them and shows what they cost once transactions are real.

## Sources

- **This repository's archive** — [ADR-0005 (transactional outbox)](../archive/04a-legal-facts/docs/adrs/0005-transactional-outbox.md) and [ADR-0004 (command ledger)](../archive/04a-legal-facts/docs/adrs/0004-command-ledger-idempotency.md). ADR-0004 identifies the limitation in lab 10 that this lab closes.

## What's next

The write side, the read side, evolution, failure, compliance, design, time, a real store, and now reliable delivery between modules.

What the sequence still has no answer for is the thing it began by refusing: **something you can run.** Every lab is verified by tests and none of them starts — until [lab21](../lab21), which gives the whole thing a shape (functional core, imperative shell, ports and adapters, one composition root) and a `bb demo` you can watch.

## Running it

This lab needs Docker.

```bash
bb all      # setup, check, test
bb test     # just the tests (starts a Postgres container)
```
