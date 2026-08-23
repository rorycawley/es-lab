# Lab 11: a process manager

[Lab 10](../lab10)'s policy reacts to one event and forgets. Plenty of real work can't: *ask the donor for stock, then load the truck with it* has to know which step it's on, what it's still waiting for, and what to do if the answer never comes.

Add state to a policy and you have a **process manager**.

```text
policy            event → [command]                  stateless
process manager   event or timer → state → [command] remembers where it is
```

The process here is a stock transfer. Truck 1 runs out of vanilla; a donor truck is asked to give ten cones up; truck 1 is then loaded with them. Two steps, two aggregates, and a wait in the middle that may never end.

## It's the decider, again

```clojure
;; lab 8, an aggregate
decide : command -> state -> [event]

;; here, a process manager
decide : state -> now -> [command]
```

Same shape, two substitutions: it emits **commands** rather than events, and it takes **time** as an input.

That reuse has a name. [Lab8](../lab8#the-shape-has-a-name) calls `decide` plus `evolve` a Decider; a process manager is one whose decisions come out as commands, which makes it [lab10](../lab10)'s `react` with a fold in front of it and a clock beside it.

The state comes from that fold, exactly as in [lab6](../lab6):

```clojure
(defmethod evolve :stock-depleted   [_ event] {:status :awaiting-unload …})
(defmethod evolve :flavour-sold     [state _] state) ; known, irrelevant context
(defmethod evolve :flavour-unloaded [state _] (assoc state :status :awaiting-load))
(defmethod evolve :truck-loaded     [state _] (assoc state :status :complete))
(defmethod evolve :default          [_ event] (throw (ex-info "Unknown event type" …)))
```

Known irrelevant facts are explicit; unknown semantics stop the reader rather than being folded and checkpointed silently. Most of this lab is machinery you already have. The two genuinely new things are how this implementation selects a process history, and how time wakes it when no event does.

## Correlation selects the observations

A process manager coordinates work across aggregate boundaries. One truck stream therefore cannot supply all the observations this transfer needs: it must see the depletion on truck 1 *and* the unload on truck 2.

The correlation id is what makes that possible, and this is the lab where it stops being a footnote:

```clojure
(store/correlated log conversation)
;; => [:flavour-sold      truck-1
;;     :stock-depleted    truck-1
;;     :flavour-unloaded  truck-2      ← a different stream
;;     :truck-loaded      truck-1]
```

One conversation, two aggregate streams, in order. [REFERENCE.md](../REFERENCE.md#layer-2--envelope) describes correlation as the id that answers *what larger process is this part of*; this manager uses that id to reconstruct its state from observed domain facts.

That is an implementation choice, not a definition of every process manager. A production manager may persist its own state, and an event-sourced manager may own a process stream as well as consuming correlated events. This lab deliberately derives its small state machine from the correlated aggregate facts so there is only one new concept on the page.

Note where the id comes from: the customer's `buy-flavour` command started the conversation, and every event and command descending from it copies the same id. The transfer isn't a new conversation — it's the same one, continuing. That's why the fold begins with `:flavour-sold`.

## It changes aggregates through commands

This manager owns real workflow rules: after depletion ask the donor, after unloading load the recipient, and at the deadline abandon. Those are coordination decisions, not mere technical routing.

What it must not do is copy the target truck's state-dependent acceptance rules. It does not inspect the donor's stock or pre-judge whether unloading is allowed; it sends a command and leaves that invariant to the truck's `decide` function. In this implementation it also changes aggregate state only through commands, so even giving up is requested as one:

```clojure
[(command correlation-id :abandon :abandon-transfer
          {:truck-id (:to state) :flavour … :reason "donor-did-not-respond"})]
```

…which the truck accepts and records as `:transfer-abandoned`. The manager folds that observation and knows it has stopped. There is no process stream **in this lab**, but that is not a universal prohibition on persisting process-manager state or facts.

That last part matters more than it looks. **The give-up has to be a fact**, or the timeout fires again on the next pass, forever.

## Time is an input

This is the first thing in these labs whose behaviour depends on *when it is asked*.

```clojure
(decide state conversation donor within)  ;; => [unload-flavour …]
(decide state conversation donor beyond)  ;; => [abandon-transfer …]
```

Same state, same events, different answer — and nothing about the log changed between those two calls. A process manager is not a function of its history alone.

Which means the clock is an argument, for exactly the reason [lab4](../lab4) gives about id generation: reaching out for `(System/currentTimeMillis)` makes the function untestable. A test that cannot move time cannot test a timeout, and the timeout is the interesting part.

But injecting `now` is only half the design. **A clock value does not wake sleeping code.** After the event checkpoint advances, there may be no further event for this conversation, so an event-only consumer would never ask whether the deadline had arrived. `run-once` therefore has two driving inputs:

```text
new event correlations ─┐
                        ├─→ re-fold active process → decide at `now`
scheduled timer tick  ──┘
```

The in-memory runner models a timer tick by polling every active correlation whenever it is invoked. A production system would usually persist a due time and use a scheduler, delayed message, or timer adapter to wake that specific process. The test advances the event checkpoint, proves the new-event batch is empty, moves the clock beyond the deadline, and still observes `:transfer-abandoned`.

## Why the timeout exists at all

Here is the part worth slowing down for.

Ask the donor for ten cones when it only has one. The truck's `decide` function refuses ([lab8](../lab8)), and this particular refusal is deliberately represented by no event ([lab5](../lab5)). No fact is appended.

So from the process manager's side, this refusal and a message that never arrived are *the same observation*: silence. There is no reply to wait for because this design did not choose to record the refusal as a fact.

```text
donor cannot spare it   →  command refused  →  nothing recorded  →  silence
network ate the command →  nothing happens  →  nothing recorded  →  silence
```

A process manager cannot distinguish those observations, and it doesn't need to. It needs a deadline. That is what a timeout answers: "how long do I wait for something that may never become a fact?" If refusals were important business observations, [lab14](../lab14#the-refusal-has-to-become-a-fact) shows the alternative: record a refusal event deliberately.

Both branches are tested: the expected `:not-enough-to-spare` refusal leaves the log unchanged and the process still `:awaiting-unload`; a later timer tick reaches the deadline and abandons. The runner catches only that named business refusal. Unknown commands, invalid identifiers, concurrency failures and other `ExceptionInfo` values remain visible instead of being mislabeled as silence.

## Redelivery, again

Each step's command id is derived, as in [lab10](../lab10), with the **step** folded into the derivation:

```clojure
(derived-command-id conversation :unload)
(derived-command-id conversation :load)
(derived-command-id conversation :abandon)
```

One process issues several commands, so a single derived id per conversation would make step two look like a redelivery of step one. Three tests pin it: stable per step, distinct across steps, distinct across conversations.

The derivation rejects a missing correlation id or invalid step rather than letting malformed inputs collapse onto a shared name. For successful transfer steps, which always record an event, causation makes redelivery recognisable and running repeatedly loads the truck once.

The refused unload exposes [lab10](../lab10#idempotency-using-the-causation-id)'s known limit: zero recorded events means no causation id to find, so a later timer poll may ask the donor again. That is an at-least-once retry, not exactly-once handling. [Lab20](../lab20#the-hole-in-lab-10) introduces the command ledger needed to remember zero-event outcomes as well.

## Identify facts before append

Lab11 keeps the recording boundary established in Labs8 and 10. The truck returns event proposals. The application runner supplies each fact's UUID, occurrence time, causation id and correlation id, preserving any other metadata. The store preserves that identity and context and assigns only storage coordinates — stream version and global position — under an atomic compare-and-append contract.

Expected version comes from the exact history folded. The in-memory log makes the transformation visible, while a production store must enforce the version check and batch write atomically.

## One gap, stated plainly

`advance-process` folds the conversation, decides, and dispatches. In this lab that all happens by returning one immutable value, so it models an atomic outcome. In a real system it is not automatically atomic: the process manager reads its state, then sends or durably records a command. Crash before the command is durable and it is lost; crash after sending but before advancing process state and it may be sent twice.

The derived command id handles the second case. The first is the **dual-write problem**, and it's the same one that appears when publishing an integration message — sketched in [lab12](../lab12) and actually solved in [lab20](../lab20), where a real transaction exists to put the command in.

The event checkpoint has the same obligations as Labs9 and 10: only advance to the last visibility-safe position actually read, and make the resulting dispatch durable before checkpointing. A crash after dispatch but before checkpointing causes redelivery; checkpointing first can lose work.

## Testing the behavior and the pure core

The process state machine and truck invariants are tested directly as pure functions of values. The runner tests then enter through its public use-case functions with the real process, domain and in-memory store; deterministic fakes supply only the identifier and clock edges. They assert externally meaningful outcomes — stock moved once, a due process wakes without a new event, metadata is preserved, and unexpected failures escape — rather than internal call counts.

A production timer, durable store or broker would receive focused adapter tests against the real technology. Only a small number of end-to-end tests are needed to prove those adapters are wired together.

## What's next

The transfer has a failure mode this lab doesn't handle. If the *second* step could fail after the first succeeded — the donor gave up ten cones and truck 1 then refused them — the stock would be in limbo, and you'd need to put it back.

That's a **compensating transaction**, and it is what "saga" meant in [its original 1987 sense](../REFERENCE.md#is-saga-a-third-thing): a long-lived transaction whose steps each have an undo. It's not needed here only because loading a truck cannot fail — [lab14](../lab14) gives the truck a capacity, at which point it can.

Before that, though: [lab3](../lab3) defined the integration message and nothing has ever published one. Getting a fact out of the log and into another module — when the log and the broker can fail independently — is [lab12](../lab12).

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
