# Lab 10: a policy

[Lab 9](../lab9) built a consumer that reads the whole log and folds it into something you *look at*. This lab builds a consumer that reads the whole log and turns it into something the system *asks for*.

That's a **policy**: the reactive rule between a fact and a request.

```text
event  →  policy  →  command
```

Event Storming draws it as the sticky between the two, and it always reads *whenever…* — *whenever a truck runs out of a flavour, load more*. It may be automated, or it may be a human following a documented procedure; the shape is the same either way.

## The shape

```clojure
;; react : event -> [command]
(defmethod react :stock-depleted
  [event]
  [{:command/id   (derived-command-id :restock-when-depleted event)
    :command/type :load-truck
    :data         {:truck-id (:stream/id event)
                   :flavour  (get-in event [:data :flavour])
                   :quantity 20}}])
```

Three things about that signature, each settled by an earlier lab.

**It takes an event and nothing else.** No store, no state, no clock. A policy is a pure function, and the tests assert that the same event produces the same commands. (A policy that needs to remember where it has got to is a *process manager* — same idea plus state, and the subject of lab 11.)

**It returns a vector**, because [lab5](../lab5)'s counting applies one message further along: an event may provoke no commands, one, or several.

**It has no opinion about most known events.** Those deliberately irrelevant facts have explicit methods returning `[]`. The `:default` method throws: an event type this reader does not understand might require a reaction, so silently checkpointing past it would lose work. Deploy readers that understand a new event before writers begin recording it, just as in [lab6](../lab6) and [lab9](../lab9).

## A policy owns the reaction; `decide` protects the target

The policy contains a business coordination rule: **when stock is depleted, request twenty more**. That rule belongs here. What it does **not** do is copy the target truck's state-dependent rules — whether the truck is on shift, has capacity, or may accept that restock.

Microsoft's CQRS Journey describes a process manager as routing messages and sometimes translating between message types. Read narrowly, that is a useful boundary: the coordinator decides *what request follows this fact*, while the target aggregate decides *whether that request is valid against its current state*.

Copying a target invariant into the policy puts the same rule in two places, and the two copies will eventually disagree. Worse, the policy's copy can suppress the command, preventing the aggregate's authoritative rule from ever running.

So the policy asks, and [lab8](../lab8)'s `decide` answers. A context-dependent business refusal belongs there while the decision is still open. Structural validation, authorization, concurrency conflicts and infrastructure failures remain different rejection boundaries; `decide` is not the one place for every kind of failure.

## The command's id is derived, not minted

[Lab4](../lab4) argued that the sender mints a command's id so a retry can reuse it. A policy has the same requirement and cannot meet it the same way, because the "sender" is a loop that may run twice over the same event.

Delivery is at-least-once. A reactor crashes between acting and recording its checkpoint, restarts, and reads the same event again. With a freshly minted id, that second reading is indistinguishable from a genuine second depletion, and the truck gets restocked twice.

So the id is **derived from the triggering event**:

```clojure
(defn derived-command-id [policy-name event]
  (UUID/nameUUIDFromBytes (.getBytes (str policy-name "/" (:event/id event)))))
```

Same event in, same command id out — so the repeat is recognisable as a repeat.

Two details worth noticing:

- **The policy's name is part of the derivation.** Two policies reacting to the same depletion — restock the truck, notify the owner — must not collide on one command id.
- **This calls for a deterministic identifier strategy.** Lab4 weighed v4 (random) against v7 (time-ordered) for newly recorded facts. Here neither will do, because a redelivery must reproduce the same value. Java's `nameUUIDFromBytes` produces a name-based UUIDv3: the policy name and triggering event id form a stable derivation contract. This id is for retry identity, not secrecy or authorization.

The triggering event must already have a valid id. Deriving from a missing value such as `nil` would make unrelated malformed events collapse onto the same command id, so the policy rejects that input explicitly.

## Identify proposals before append

The message changes shape at each boundary:

```text
policy           event -> command
domain           command + state -> event proposals
application      proposals -> identified events with causation
store            identified events -> versioned, positioned records
```

The domain remains pure and proposes facts without allocating identifiers. The application runner supplies each `:event/id` and merges the command's id into `:metadata :causation-id` **before** append. The store preserves both and assigns only storage-owned coordinates: `:stream/version` and `:event/position`. A retry must retain the same identified batch; the immutable runner is a compact model of that boundary rather than a production retry loop.

## Idempotency, using the causation id

A derived id makes the duplicate *recognisable*. Something still has to do the recognising.

The store already has what's needed, because every event records the command that caused it — that is what [`:causation-id`](../REFERENCE.md#layer-2--envelope) is for, and this is the lab where it becomes concrete:

```clojure
(defn dispatch [log gen-id command]
  (if (store/caused-by? log (:command/id command))
    log                                    ; already acted; nothing to do
    (handle log gen-id command)))
```

The check reads the event log itself. For this one policy, whose positive restock command is guaranteed to record an event, finding its causation id proves that command has already run. This is a deliberately narrow shortcut, not the general definition of idempotency.

There is a hole in that, and [lab20](../lab20) closes it: *if the events a command would have produced* assumes it produces some. [Lab5](../lab5) says it need not, and a command that legitimately records nothing leaves no causation trail to find. If its triggering event is redelivered — for example after a lost checkpoint — that command executes again. This policy never hits the hole because its restock quantity is always positive. The general answer is a command ledger keyed by `:command/id`, recorded atomically whether the result contains zero, one or many events.

The test is the crash case: react to the same batch twice from the same checkpoint, and the truck is restocked once. A production implementation must coordinate duplicate recognition and append; the immutable value returned here only models that outcome.

## Checkpoint what you read, not what exists

A subtle one, and it bites in production rather than in tests.

Dispatching *appends*. So by the time a pass finishes, the log is longer than it was when the batch was read:

```text
position   event                       when
   1       truck-loaded                before the pass
   2       flavour-sold                before the pass
   3       stock-depleted              before the pass   ← batch read to here
   4       truck-loaded (the restock)  during the pass
```

Checkpointing at the *new end of the log* would record 4 even though the policy never read position 4. It would skip its own output here; in production it can also skip an event another writer appended after the batch was selected. The checkpoint has to be the last position **read**, which is 3.

```clojure
:checkpoint (->> batch (map :event/position) (apply max checkpoint))
```

The reactor will see position 4 on its next pass, which is correct: its own output is just more log, and it is entitled to look at it.

There are two further production obligations hidden by this in-memory value:

- The batch must be **visibility-safe**. As [lab9](../lab9) showed, global positions may contain gaps, so a consumer must not checkpoint past a position whose event is not yet visible.
- Dispatch must become durable **before** the checkpoint advances. If the reactor crashes after append but before checkpointing, it reads the event again and the deduplication path handles it. Checkpointing first would lose the command permanently. When log and checkpoint live in different systems, this is an at-least-once workflow rather than one atomic transaction.

## Circular logic

Which raises the obvious question. If a policy's output comes back round as input, what stops it running forever?

Nothing structural. Microsoft lists it as a hazard of the pattern, and the only thing preventing it here is a property of this particular policy:

```text
triggers on:  :stock-depleted
produces:     :truck-loaded   →  which triggers nothing
```

The trigger set and the output set don't overlap, so the second pass produces no commands and the reactor goes quiet. That is a design obligation, not a guarantee. The tests exercise the real policy through the runner, assert its trigger and ignored output explicitly, and show that it settles after one productive pass.

`run-until-quiet` still takes a defensive bound rather than looping on `while`: a future policy whose outputs trigger itself should fail loudly rather than hang a reactor.

## Test behavior at the boundary, purity at the core

The runner tests enter through the reactor's public functions and use the real policy, domain fold and in-memory store together. The only fake is the secondary identifier port: a deterministic UUID generator makes results reproducible without asserting internal calls. Those behavior tests can survive a reorganization of the runner as long as the use case still restocks once and checkpoints correctly.

The pure core is also worth testing directly. `policy_test` calls `react` because a pure event-to-command rule is an inexpensive, stable unit with no technology to fake. It checks business outputs and explicit event semantics, not private helpers or interaction counts. A real database-backed store would receive separate adapter tests, while a deployed primary adapter would need only a few end-to-end wiring checks.

## What's next

A policy reacts to one event and forgets. Plenty of real processes can't: *reserve the seats, take the payment, confirm the booking* needs to know which step it is on, what it is still waiting for, and what to do if the payment never arrives.

Add state to a policy and you have a **process manager** — its own `evolve`, and a decision function shaped exactly like [lab8](../lab8)'s. That's [lab11](../lab11), along with the one genuinely new ingredient: reacting to *time*, not just to events.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
