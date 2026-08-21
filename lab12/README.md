# Lab 12: publishing

[Lab 3](../lab3) defined the integration message. Eight labs later, nothing has ever sent one.

This lab does — and the interesting part is not the sending. It's that recording a fact and telling somebody about it are **two writes to two systems that fail independently**, and no ordering of those two writes is safe.

## The dual write

Say the command handler publishes as it goes. There are two ways to arrange it, and both are broken:

```text
append to log  →  💥  →  publish        the sale happened; nobody was told
publish        →  💥  →  append to log   everyone was told about a sale that didn't happen
```

The second is worse — you've announced a fact that isn't in your own history — but neither is acceptable. And you cannot fix it by wrapping both in a transaction, because a message broker isn't in your database's transaction.

The standard answer is the **transactional outbox**: don't write to the broker at all. Write the outgoing message into your *own* store, in the same transaction as the state change, so there is only ever one write. A separate process reads the outbox and publishes.

(Worth admitting where this lab stands: the store here is an in-memory vector, so *the failure being argued about cannot happen in it*. The argument is sound and the demonstration is deferred — [lab20](../lab20) has real transactions and shows what each ordering costs.)

## The event log can be the relay source

Here is the payoff for everything since lab 6.

The outbox pattern exists because the state change and the outgoing contract are separate records that must be made atomic. An event-sourced system sometimes avoids that second write: the fact is already durable, ordered and positioned ([lab9](../lab9)), so a relay can derive integration messages from it after commit.

So the relay is lab 9's projection with a different destination:

| | projection (lab 9) | relay (here) |
|---|---|---|
| reads | the log, since a checkpoint | the log, since a checkpoint |
| produces | a read model | messages someone else receives |
| on catch-up | folds what it missed | publishes what it missed |
| must be | idempotent | at-least-once, so *consumers* must be |

`store.clj` in this lab gains **nothing for publishing** — no outbox table, no `sent` flag, no second write. The relay consumes the event log through a checkpoint, while the event-recording application boundary remains responsible for identity, occurrence time, causation and correlation.

That substitution is conditional. It is safe only while all of these are true:

- every outgoing message is deterministically derivable from a retained event;
- the translation used for an old event remains available and produces the contract intended for that event;
- the relay can resume from a visibility-safe cursor; and
- republishing old facts is acceptable under the consumer's idempotency contract.

A separate transactional outbox is therefore not merely an optimisation. It freezes the exact payload, recipient, contract version and message identity at command-handling time; it also permits independent retention, targeted retries, and messages that are not derivable from a domain event. [Lab20](../lab20) uses that design once a real database transaction exists. This lab deliberately demonstrates the leaner **event log as relay source** option and states its constraints.

## The contract is a translation, and it lives in one file

```clojure
(defmethod announce :stock-depleted
  [event]
  [{:message/type :flavour-unavailable   ; the customer app: grey out a button
    :recipient    :customer-app
    :payload      {:event/id … :truck-id … :flavour …}}
   {:message/type :restock-required      ; purchasing: order more
    :recipient    :purchasing
    :payload      {:event/id … :truck-id … :flavour …}}])

(defmethod announce :flavour-sold [_event] [])
(defmethod announce :truck-loaded [_event] [])
(defmethod announce :default [event] (throw (ex-info "Unknown event type" …)))
```

Three things this makes real that earlier labs only asserted.

**Most known facts are published to nobody.** Explicit methods return `[]` for facts deliberately kept private, and the truck's own log bears it out: three events recorded, one announced. The multimethod default throws because an unknown event might require a contract; silently checkpointing it would make the omission permanent. Deploy relay readers before writers begin recording a new event type.

**One fact, several addressed messages.** A depletion means two different things to two modules, and each gets a recipient plus a contract shaped for it. Lab5 showed the cardinality; here it runs.

**The domain shape stays private.** A test pins the payload keys at exactly `#{:event/id :truck-id :flavour}` — no `:data`, no `:stream/version`, no event metadata. Consumers depend on this contract and not the truck's internal event shape, which is what lets the domain be refactored without accidentally changing an external promise. That is why [lab3](../lab3) introduced a third shape rather than publishing the domain event.

## The message id is stamped at send, not in the contract

`announce` returns messages with **no** `:message/id`. A test asserts their keys are exactly `#{:message/type :payload}`.

That's [lab4](../lab4)'s distinction, now structural: `:message/id` identifies a newly created envelope, so the pure contract translation cannot know it. The relay creates the complete envelope at the sending boundary and rejects an invalid generated UUID. Republishing the same fact creates a new envelope with a new message id; a broker redelivering an already-created envelope would retain its existing id.

What travels unchanged is `:event/id`, inside the payload, where the receiving module reads it as data. Envelope metadata also carries the conversation's correlation id and names the source event id as immediate causation. Those answer *what larger work is this part of?* and *what directly caused this message?* respectively.

## At-least-once, and who pays for it

Now the failure that survives the outbox.

The relay publishes, then records its checkpoint. Crash in between, restart, and it publishes the same batch again. You cannot close this gap by reordering — checkpoint first and a crash loses the message entirely, which is worse.

```clojure
(let [first-pass  (relay/run-once log 0 empty-broker gen-id)
      second-pass (relay/run-once log 0 (:broker first-pass) gen-id)]
  ;; four deliveries, four :message/id values, one :event/id
  )
```

So delivery is **at-least-once**. Exactly-once *delivery* is not achievable across an ordinary network boundary; exactly-once *effect* requires a durable inbox record committed atomically with the consumer's effect. This lab's immutable model illustrates the deduplication key:

```clojure
(defn receive [model message]
  (if (not= :customer-app (:recipient message))
    model
    (let [event-id (get-in message [:payload :event/id])]
      (if (contains? (:seen model) event-id)
        model
        (-> model (apply-message message) (update :seen conj event-id))))))
```

**Deduplicate per recipient and fact, not merely on the envelope.** A republish creates another envelope, so `:message/id` alone does not suppress it. The recipient is equally important: one fact intentionally goes to two modules and each must handle it once. The customer consumer ignores purchasing's message without marking the fact seen; a test reverses delivery order to prove the wrong recipient cannot poison its inbox.

The fact id is the correct key for this contract because each recipient has one intended effect per source fact. If a recipient legitimately needed two independent effects from the same fact, the contract would need a more specific stable operation key. Idempotency keys follow the effect boundary; they are not chosen by slogan.

The `:seen` set and read-model update are one immutable value here, so they cannot tear apart. A production consumer must put the inbox row and real side effect in the same transaction; otherwise a crash after the effect but before marking it seen still duplicates work. [Lab20](../lab20#the-inbox-and-what-lab-12s-consumer-could-not-do) makes that durability requirement real.

## What this still doesn't solve

Two gaps, both stated rather than fixed:

**The visibility gap.** The relay polls by position, so it inherits the trap from [lab9](../lab9) and [REFERENCE.md](../REFERENCE.md#the-visibility-gap-in-global_position) — shown for real in [lab19](../lab19): a sequence value is assigned at INSERT and becomes visible at COMMIT, so a relay can checkpoint past an event that has not committed yet. Here that can mean a message is never sent. A production read must return a visibility-safe upper bound, not merely every currently visible row.

**Checkpoint durability.** Publish must become durable before the relay advances its checkpoint. A crash after publish causes a republish, which the recipient absorbs; checkpointing first can lose the message permanently. The returned broker and checkpoint are values in this lab, not a claim that two production systems share a transaction.

**Ordering across streams.** The relay publishes in position order, and brokers generally do not preserve global order. This contract's consumer only adds a flavour to a set, so it does not need ordering and the payload intentionally omits stream version. A future contract that needs per-entity ordering must expose a suitable sequence or version explicitly rather than relying on arrival order.

**Contract drift.** Because translation happens at relay time, deploying a changed `announce` function can change what an old, not-yet-published event produces. Keep historical translation behavior available, version the contract deliberately, or freeze outgoing envelopes in a transactional outbox. Replaying the event log under today's arbitrary code is not automatically a faithful reconstruction of what should have been sent yesterday.

## Testing the boundary

The contract tests exercise the pure event-to-message translation directly. Relay behavior tests use the real contract, consumer and in-memory log, with deterministic identifier fakes at the event and envelope boundaries. They assert recipients, payloads, causation, correlation, checkpoint behavior and duplicate effects rather than internal calls.

A production log reader, broker publisher and inbox need focused adapter tests against their real technologies. A few end-to-end tests then prove the wiring; they should not carry the entire business-contract suite.

## What's next

The write side is complete, the read side is complete, and facts now leave the building under a contract.

What hasn't been faced is **time passing**: an event type whose shape needs to change while a decade of the old shape stays in the log and stays readable. Every lab so far has assumed one schema, forever. That's [lab13](../lab13).

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
