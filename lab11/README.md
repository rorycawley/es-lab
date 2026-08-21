# Lab 11: a process manager

[Lab 10](../lab10)'s policy reacts to one event and forgets. Plenty of real work can't: *ask the donor for stock, then load the truck with it* has to know which step it's on, what it's still waiting for, and what to do if the answer never comes.

Add state to a policy and you have a **process manager**.

```text
policy            event → command                    stateless
process manager   event → state → command            remembers where it is
```

The process here is a stock transfer. Truck 1 runs out of vanilla; a donor truck is asked to give ten cones up; truck 1 is then loaded with them. Two steps, two aggregates, and a wait in the middle that may never end.

## It's the decider, again

```clojure
;; lab 8, an aggregate
decide : command -> state -> [event]

;; here, a process manager
decide : state -> now -> [command]
```

Same shape, two substitutions: it emits **commands** rather than events, and it takes **time** as an input. The state comes from a fold, exactly as in [lab6](../lab6):

```clojure
(defmethod evolve :stock-depleted   [_ event] {:status :awaiting-unload …})
(defmethod evolve :flavour-unloaded [state _] (assoc state :status :awaiting-load))
(defmethod evolve :truck-loaded     [state _] (assoc state :status :complete))
(defmethod evolve :default          [state _] state)
```

Most of this lab is machinery you already have. The two genuinely new things are where the process's history comes from, and time.

## Its history is a correlation, not a stream

A process manager spans aggregates — that is the entire reason it exists — so its history cannot be a stream. `:stream/id` scopes to one truck ([lab7](../lab7)), and this process needs the depletion on truck 1 *and* the unload on truck 2 in one fold.

The correlation id is what makes that possible, and this is the lab where it stops being a footnote:

```clojure
(store/correlated log conversation)
;; => [:flavour-sold      truck-1
;;     :stock-depleted    truck-1
;;     :flavour-unloaded  truck-2      ← a different stream
;;     :truck-loaded      truck-1]
```

One conversation, two streams, in order. [REFERENCE.md](../REFERENCE.md#layer-2--envelope) describes correlation as the id that answers *what larger process is this part of*; a process manager is that larger process, made concrete.

Note where the id comes from: the customer's `buy-flavour` command started the conversation, and every event and command descending from it copies the same id. The transfer isn't a new conversation — it's the same one, continuing. That's why the fold begins with `:flavour-sold`.

## It only ever issues commands

The process manager never writes an event. Every fact in the log was still decided by the truck aggregate, and a test asserts there is no process-manager stream at all.

This isn't fastidiousness. It falls directly out of [lab2](../lab2#two-scoping-notes)'s constraint — a process manager routes; it does not decide. Writing its own facts *is* deciding. So even giving up is a command:

```clojure
[(command correlation-id :abandon :abandon-transfer
          {:truck-id (:to state) :flavour … :reason "donor-did-not-respond"})]
```

…which the truck decides on and records as `:transfer-abandoned`. The process manager then folds that event and knows it has stopped.

That last part matters more than it looks. **The give-up has to be a fact**, or the timeout fires again on the next pass, forever.

## Time is an input

This is the first thing in these labs whose behaviour depends on *when it is asked*.

```clojure
(decide state conversation donor within)  ;; => [unload-flavour …]
(decide state conversation donor beyond)  ;; => [abandon-transfer …]
```

Same state, same events, different answer — and nothing about the log changed between those two calls. A process manager is not a function of its history alone.

Which means the clock is an argument, for exactly the reason [lab4](../lab4) gives about id generation: reaching out for `(System/currentTimeMillis)` makes the function untestable. A test that cannot move time cannot test a timeout, and the timeout is the interesting part.

## Why the timeout exists at all

Here is the part worth slowing down for.

Ask the donor for ten cones when it only has one. `decide` refuses ([lab8](../lab8)) — and a refusal **records nothing** ([lab5](../lab5)). No event, no trace, nothing appended.

So from the process manager's side, a refusal and a message that never arrived are *the same observation*: silence. There is no reply to wait for, because refusals aren't facts.

```text
donor cannot spare it   →  command refused  →  nothing recorded  →  silence
network ate the command →  nothing happens  →  nothing recorded  →  silence
```

A process manager cannot distinguish those, and it doesn't need to. It needs a deadline. That is what a timeout *is*: the answer to "how long do I wait for something that may never be a fact?"

Both branches are tested — the refusal leaves the log unchanged and the process still `:awaiting-unload`; move the clock past the deadline and it abandons.

## Redelivery, again

Each step's command id is derived, as in [lab10](../lab10), with the **step** folded into the derivation:

```clojure
(derived-command-id conversation :unload)
(derived-command-id conversation :load)
(derived-command-id conversation :abandon)
```

One process issues several commands, so a single derived id per conversation would make step two look like a redelivery of step one. Three tests pin it: stable per step, distinct across steps, distinct across conversations.

The pass is idempotent as a result — running it three times over the same log loads the truck once.

## One gap, stated plainly

`advance-process` folds the conversation, decides, and dispatches. In this lab that all happens in memory, so it is effectively atomic. In a real system it is not: the process manager reads the log, then sends a command over a network. Crash in between and the command is lost; crash after sending but before recording and it may be sent twice.

The derived command id handles the second case. The first is the **dual-write problem**, and it's the same one that appears when publishing an integration message — sketched in [lab12](../lab12) and actually solved in [lab20](../lab20), where a real transaction exists to put the command in.

## What's next

The transfer has a failure mode this lab doesn't handle. If the *second* step could fail after the first succeeded — the donor gave up ten cones and truck 1 then refused them — the stock would be in limbo, and you'd need to put it back.

That's a **compensating transaction**, and it is what "saga" meant in [its original 1987 sense](../REFERENCE.md#is-saga-a-third-thing): a long-lived transaction whose steps each have an undo. It's not needed here only because loading a truck cannot fail — [lab14](../lab14) gives the truck a capacity, at which point it can.

Before that, though: [lab3](../lab3) defined the integration message and nothing has ever published one. Getting a fact out of the log and into another module — when the log and the broker can fail independently — is [lab12](../lab12).

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
