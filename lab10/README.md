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

**It has no opinion about most events.** The `:default` method returns `[]`, exactly the shrug `evolve` gives in [lab6](../lab6). A stream is full of facts a given reactor doesn't care about.

## A policy routes; it does not decide

The most important line in this lab is one that isn't written. The policy above does **not** check whether the depot has stock, whether the truck is on shift, or whether restocking is allowed at all.

Microsoft's CQRS Journey states the constraint plainly: a process manager "does not perform any business logic. It only routes messages, and in some cases translates between message types." The same holds for its stateless sibling.

The reason is not purity for its own sake. Business logic in a policy is business logic in *two* places — here and in `decide` — and the two will disagree. When they do, the policy's copy wins silently, because it decides whether the command is ever sent, and `decide`'s copy never gets the chance to run. Rules that never run are rules nobody notices are wrong.

So the policy asks, and [lab8](../lab8)'s `decide` answers. If the request turns out to be disallowed, it is refused there, in the one place refusals belong.

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
- **This is a third reason to choose a UUID version.** Lab4 weighed v4 (random) against v7 (time-ordered) for *facts*. Here neither will do, because both are unpredictable by construction and unpredictability is precisely what we don't want. A name-based UUID is the right tool: the id is a *function of its input*, which is the whole point.

## Idempotency, using the causation id

A derived id makes the duplicate *recognisable*. Something still has to do the recognising.

The store already has what's needed, because every event records the command that caused it — that is what [`:causation-id`](../REFERENCE.md#layer-2--envelope) is for, and this is the lab where it becomes concrete:

```clojure
(defn dispatch [log gen-id command]
  (if (store/caused-by? log (:command/id command))
    log                                    ; already acted; nothing to do
    (handle log gen-id command)))
```

The check reads the event log itself. No table of processed commands, no inbox, no extra store to keep in step — if the events a command would have produced are already there, the command has already run.

There is a hole in that, and [lab20](../lab20) closes it: *if the events a command would have produced* assumes it produces some. [Lab5](../lab5) says it need not, and a command that legitimately records nothing leaves no causation trail to find — so it re-runs on every pass, forever. This policy never hits it, because its restock quantity is always positive. The general answer is a ledger keyed by the command id.

The test is the crash case: react to the same batch twice from the same checkpoint, and the truck is restocked once.

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

Checkpointing at the *end of the log* would record 4 — and quietly skip anything another writer landed at position 4 while this pass was running. The checkpoint has to be the last position **read**, which is 3.

```clojure
:checkpoint (->> batch (map :event/position) (apply max checkpoint))
```

The reactor will see position 4 on its next pass, which is correct: its own output is just more log, and it is entitled to look at it.

## Circular logic

Which raises the obvious question. If a policy's output comes back round as input, what stops it running forever?

Nothing structural. Microsoft lists it as a hazard of the pattern, and the only thing preventing it here is a property of this particular policy:

```text
triggers on:  :stock-depleted
produces:     :truck-loaded   →  which triggers nothing
```

The trigger set and the output set don't overlap, so the second pass produces no commands and the reactor goes quiet. That is a design obligation, not a guarantee — and the tests assert both halves: that this policy settles in one pass, and that a policy reacting to its own output does not settle at all.

That's why `run-until-quiet` takes a bound rather than looping on `while`. A runaway policy should fail loudly in a test, not hang a production reactor.

## What's next

A policy reacts to one event and forgets. Plenty of real processes can't: *reserve the seats, take the payment, confirm the booking* needs to know which step it is on, what it is still waiting for, and what to do if the payment never arrives.

Add state to a policy and you have a **process manager** — its own `evolve`, and a decision function shaped exactly like [lab8](../lab8)'s. That's [lab11](../lab11), along with the one genuinely new ingredient: reacting to *time*, not just to events.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
