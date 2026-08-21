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

## An event-sourced system already has an outbox

Here is the payoff for everything since lab 6.

The outbox pattern exists because, in a CRUD system, the state change and the message are separate things that must be made atomic. Event sourcing doesn't have that problem: **the fact is already a durable, ordered, positioned record** ([lab9](../lab9)). There is nothing to keep in step with it, because it *is* the thing.

So the relay is lab 9's projection with a different destination:

| | projection (lab 9) | relay (here) |
|---|---|---|
| reads | the log, since a checkpoint | the log, since a checkpoint |
| produces | a read model | messages someone else receives |
| on catch-up | folds what it missed | publishes what it missed |
| must be | idempotent | at-least-once, so *consumers* must be |

`store.clj` in this lab gained **nothing** to support publishing — no outbox table, no `sent` flag, no second write. It is lab 11's store with the two process-manager helpers dropped, and that is the point.

(A separate outbox table still earns its place sometimes — when messages aren't derivable from events, or need their own retention. But it's an optimisation, not the mechanism.)

## The contract is a translation, and it lives in one file

```clojure
(defmethod announce :stock-depleted
  [event]
  [{:message/type :flavour-unavailable   ; the customer app: grey out a button
    :payload      {:event/id … :truck-id … :flavour …}}
   {:message/type :restock-required      ; purchasing: order more
    :payload      {:event/id … :truck-id … :flavour …}}])

(defmethod announce :default [_event] [])
```

Three things this makes real that earlier labs only asserted.

**Most facts are published to nobody.** The `:default` returns `[]`, and the truck's own log bears it out: three events recorded, one of them announced. Selling a cone is the truck's business. [Lab5](../lab5) called this the default and it still is.

**One fact, several messages.** A depletion means two different things to two different modules, and each gets a contract shaped for it. Lab5 showed this as data; here it runs.

**The domain shape stays private.** A test pins the payload keys at exactly `#{:event/id :truck-id :flavour}` — no `:data`, no `:stream/version`, no metadata. Consumers depend on this namespace and nothing else in the system, which is what lets the domain be refactored without breaking them. That is the whole reason [lab3](../lab3) introduced a third shape rather than publishing the domain event.

## The message id is stamped at send, not in the contract

`announce` returns messages with **no** `:message/id`. A test asserts their keys are exactly `#{:message/type :payload}`.

That's [lab4](../lab4)'s distinction, now structural: `:message/id` identifies a *delivery*, so the contract cannot know it — the same fact published twice is two deliveries. The relay stamps it on the way out, exactly as [lab8](../lab8)'s store stamps identity on events that `decide` produced without one.

What travels unchanged is `:event/id`, inside the payload, where the receiving module reads it as data.

## At-least-once, and who pays for it

Now the failure that survives the outbox.

The relay publishes, then records its checkpoint. Crash in between, restart, and it publishes the same batch again. You cannot close this gap by reordering — checkpoint first and a crash loses the message entirely, which is worse.

```clojure
(let [first-pass  (relay/run-once log 0 empty-broker gen-id)
      second-pass (relay/run-once log 0 (:broker first-pass) gen-id)]
  ;; four deliveries, four :message/id values, one :event/id
  )
```

So delivery is **at-least-once**, and that is the best anyone can do. Exactly-once *delivery* is not achievable across a network; exactly-once *processing* is, and the cost is paid by the consumer:

```clojure
(defn receive [model message]
  (let [event-id (get-in message [:payload :event/id])]
    (if (contains? (:seen model) event-id)
      model
      (-> model (apply-message message) (update :seen conj event-id)))))
```

**Deduplicate on the fact, not the envelope.** There's a test for the wrong version: dedupe on `:message/id` and all four deliveries look new, because a republish genuinely *is* a new message. That's lab4's argument, and this is the first lab where getting it wrong produces a visible wrong answer.

## What this still doesn't solve

Two gaps, both stated rather than fixed:

**The visibility gap.** The relay polls by position, so it inherits the trap from [lab9](../lab9) and [REFERENCE.md](../REFERENCE.md#the-visibility-gap-in-global_position) — shown for real in [lab19](../lab19): a sequence value is assigned at INSERT and becomes visible at COMMIT, so a relay can checkpoint past an event that hasn't landed yet. In a projection that means a wrong read model. Here it means **a message that is never sent**, which is worse, because the other module's state is now wrong and nothing in your system knows.

**Ordering across streams.** The relay publishes in position order, and brokers generally don't preserve it. Consumers that need per-entity ordering have to get it from the message content — a version, a sequence — not from arrival order.

## What's next

The write side is complete, the read side is complete, and facts now leave the building under a contract.

What hasn't been faced is **time passing**: an event type whose shape needs to change while a decade of the old shape stays in the log and stays readable. Every lab so far has assumed one schema, forever. That's [lab13](../lab13).

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
