# Lab 2: a command

This lab explores what a command is, and how it relates to the event from [lab1](../lab1).

> **A command is a request to change state, addressed to one handler, which may refuse it.**

Three clauses, and every property below follows from one of them. *(The reduction to three clauses is this lab's framing; the properties are drawn from Young, Dahan and Evans, attributed where they appear.)*

The domain is still the Ice Cream truck. Banking shows up in two places — `WithdrawMoney`, an overdraft — because those are the canonical illustrations and readers already have the intuition; the truck version is given alongside.

---

## Because it is a request

### A request, not a fact

A command is a request: *please do this*. An event is a fact: *this happened*.

The distinction is not stylistic. It's about what can still go wrong:

```text
COMMAND                         EVENT
────────                        ─────
:buy-flavour                    :flavour-sold
imperative / request            past-tense fact
may be rejected                 has already happened
sent to one handler             published to anyone
```

A command arrives at the domain from a caller and is a claim about what someone *wants*. The caller may be a person, another module, or the system's own policy; the command can still be refused — no chocolate left, the truck is closed, the customer has no money. An event leaves the decision as a claim about what *happened*. Nothing downstream is entitled to argue with it.

Collapsing the two is the most common way an event-sourced design goes wrong. If a `:flavour-sold` event can be rejected, it wasn't an event; it was a command wearing the wrong name.

### Named as an imperative, and why

`BuyFlavour`, not `FlavourSold`. `WithdrawMoney`, not `MoneyWithdrawn`.

This isn't a style rule. Naming commands in the imperative shows *linguistically* that the receiver is allowed to reject them. If it weren't allowed to, the thing would be an event instead. **The grammar encodes the permission to refuse** — which means a badly named command quietly misrepresents the contract, and reviewers can catch it by reading the name alone.

It is the mirror of [lab1](../lab1#past-tense-and-its-load-bearing)'s rule. Past tense says *you may not argue with this*; the imperative says *you may*.

### Naming is domain work

Don't default to `ChangeAddress`. Ask whether **correcting** an address — it was typed wrong — differs from **relocating** a customer, who has moved. They produce different events, trigger different notifications, and mean different things to the business; one name for both throws that away at the doorstep.

That is the command side of [lab1's granularity argument](../lab1#name-granularity-is-irreversible), and it is where the damage originates. A coarse command name is what produces a coarse event name, and the event is the thing you keep forever.

Commands generally align with **use cases**, which makes use cases the place to start. If a command doesn't correspond to something a person actually does, it's likely a fragment of implementation that has escaped into the model.

### Not part of your source of truth

Commands may well be persisted — durable queues, idempotency tables, audit trails, replay-for-debugging. That's normal and often necessary.

But you cannot rebuild state from them. A command is a request that *might* have been refused, so replaying a log of commands re-runs every decision against whatever the rules say today, which is a different system from the one that ran yesterday. Events replay to the same state forever; commands replay to whatever today's rules decide.

("Forever" holding the fold constant, at least. Change `evolve`'s shape and the same events fold to a different state — [lab17](../lab17) — which is why a *snapshot* has to record which fold produced it. The events themselves are still exactly what they were, which is the difference that matters here.)

The accurate phrasing is **"not the source of truth"** rather than "discarded." ([REFERENCE.md](../REFERENCE.md#what-it-wont-answer) works through the one case where a command log is genuinely tempting, and why modelling the refusal as an event beats it.)

---

## Because it is addressed to one handler

### The address

Commands are routed to something, so every state-changing command needs an **address**: the id of what it is asking to change. The id may be carried in the command data at this stage, in an envelope, or in the transport route; it must be unambiguous by the time the handler is selected.

```clojure
{:command/type :buy-flavour
 :data         {:truck-id #uuid "0f1c2b3a-…"    ; ← the address
                :flavour  "vanilla"}}
```

Greg Young's further point applies when the command creates a new aggregate: the **client** should originate that target id, normally as a UUID. That looks like a small detail and isn't: a client-generated id lets the caller name the aggregate *before it exists*, which is what makes retries safe. If the receiver mints the id, a retried "create" produces a second thing; if the client mints it, the retry addresses the same thing and can be recognised as a repeat. Commands for an existing truck reuse its already assigned id; the client does not invent a new target for every action.

Two things the address is *not*:

- **Not the command's own identity.** The address says which truck; `:command/id` — [lab4](../lab4) — says which request, so that one request delivered twice isn't mistaken for two.
- **Not permanently `:data`.** It sits in `:data` here because which truck you're asking is part of what you're asking. Once there is a fleet, the same id becomes `:stream/id` in the envelope and decides which history the resulting events belong to ([lab7](../lab7)) — the same migration [lab1](../lab1#what-an-event-carries) describes for the event side.

### Sent, not published

The client **sends** commands; it does not publish them. Publishing is reserved for events, which state a fact whose publisher doesn't care how anyone reacts.

```text
COMMAND    many senders   →   one handler       "you, do this"
EVENT      one publisher  →   many subscribers  "this happened, do as you like"
```

Exactly inverted. A command has one logical owner, typically a single consumer, and the sender expects it to be dealt with. If you find yourself broadcasting a command to whoever's listening, either it's an event, or you've lost track of who is responsible for the state it changes.

---

## Because it may be refused

### Two kinds of rejection, in two places

A command can be refused for two very different reasons, and they belong at different depths.

The useful distinction — usually credited to Udi Dahan — is between **validation**, which states a context-independent fact about a command, and **business rules**, which are context-dependent and can only be judged against state.

The operational test: *could I answer this holding the command and nothing else — no database, no network?*

```text
Is :quantity a positive number?       command alone      validation
Is :truck-id a UUID?                  command alone      validation
Is :flavour present at all?           command alone      validation
────────────────────────────────────────────────────────────────────
Is there enough vanilla left?         needs state        business rule
Is this truck still on shift?         needs state        business rule
Is :flavour one this truck sells?     needs state        business rule
```

That last row is the instructive one, because it *looks* like validation. "Is this a real flavour?" feels like a fact about the message — but the set of flavours a particular truck sells is part of current domain state. An answer whose truth can change while the command stays identical is a business rule, however static it feels.

The first group can be checked at the adapter — HTTP handler, queue consumer — before the value is accepted as a command and handed to the domain, because nothing about current state can change the answer. The second group cannot be checked anywhere but against rehydrated state, which is `decide`'s job in [lab8](../lab8). [Lab22](../lab22) builds both edges and shows that a command can be perfectly valid and still correctly refused.

Getting this wrong in either direction hurts. Validating `:quantity` deep in the domain means the domain is full of checks that could never have failed if the edge had done its job. Checking the stock at the edge means checking it against state you haven't loaded, and being wrong.

### A decision is not safe until the append succeeds

*(This framing is this lab's, though the mechanism it rests on is straight out of Greg Young's event store design.)*

Here's the trap. `decide` checks the business rules against rehydrated state and allows the command. That verdict is correct for the state it saw, but it cannot guarantee the state is still current: two tills serving the last cone both fold the same history, both see one cone left, and both conclude the sale is fine. (The canonical version is two withdrawals against one balance — same shape.)

What closes the gap is the check at **write** time. The store compares the version the writer read against the version the stream is actually at, and rejects the append if they differ — before the events are inserted. That's [lab7](../lab7).

So safe command handling has two gates:

```text
decide   "given what I read, the domain allows this"
append   "nothing has happened since I read it"
```

The append gate does not make a second business decision. A conflict means *the first decision used stale state*: read, fold, and decide again. [Lab23](../lab23#status-codes-are-lab-2s-two-columns) therefore reports it as **409**, distinct from the domain's **422** refusal.

### A refusal may itself be a fact

Refusing a command usually records nothing — that's [lab5](../lab5)'s argument, and the default. [Lab14](../lab14#the-refusal-has-to-become-a-fact) implements the exception when a process manager needs to observe the refusal.

But not always. `WithdrawalDeclined` is a legitimate event if overdraft attempts matter to the business, and they often do: fraud detection, dunning, "you were declined three times this week" support calls. Likewise a truck that keeps being asked for pistachio it doesn't stock — if the owner would restock on the strength of it, the refusals are worth recording.

The test is the same one [lab1](../lab1#selection-is-a-filter) applies to every event: does a domain expert want to track or be notified of this? **Failure is not the same as silence.** It just usually is.

---

## Two scoping notes

**A command isn't only for external actors.** Plenty of commands are issued by the system to itself, in reaction to its own events. "Command" describes the message's contract — one addressee, may be refused — not where it came from.

Event Storming has the precise name for the thing that does this: a **policy**. It's the purple sticky between an event and a command, and it always reads *"whenever…"* — *whenever a customer places an order, check their credit limit*. A policy is exactly the reactive rule that turns a fact into a request, and it may be automated or a human following a documented procedure.

When a policy has to remember where it has got to, it needs state, and *Enterprise Integration Patterns* calls that a **process manager**: a central unit that maintains state and determines the next step from intermediate results. Microsoft's CQRS Journey adds a constraint worth keeping: a process manager "does not perform any business logic. It only routes messages, and in some cases translates between message types." Decisions belong in aggregates; the process manager only says what happens next.

Those two words have stable definitions. **"Saga" does not** — it is used for the stateless coordinator, the stateful one, and the compensation mechanism, by sources that contradict each other. Prefer `policy`, `process manager` and `compensating transaction`, and if someone says "saga", ask which they mean. [REFERENCE.md](../REFERENCE.md#is-saga-a-third-thing) works through why the definitions collide.

**"Command" isn't Evans's term.** In the DDD Reference, commands are *methods which result in modifications to observable state* — Bertrand Meyer's command-query separation applied at the method level, sitting opposite side-effect-free functions. The message-object sense used throughout these labs is Greg Young's. Both are current; they are not the same idea, and DDD-literate readers will have the other one in mind.

---

## The shape

The name of an operation, and the data required to perform it.

```clojure
(def buy-flavour-vanilla-command
  {:command/type :buy-flavour
   :data         {:flavour "vanilla"}})
```

A useful way to read it is as a **serializable method call** — the name of something to invoke, plus its arguments. But that's what a command *represents*, not what it *is*. Like the event in [lab1](../lab1#its-just-a-value), it is a value: no behaviour, no methods, nothing to invoke. The "call" happens somewhere else entirely, in a function that takes this map as an argument.

### Carry what the behaviour needs, not the whole entity

```clojure
;; Do
{:command/type :buy-flavour
 :data         {:truck-id #uuid "0f1c2b3a-…" :flavour "vanilla"}}

;; Don't
{:command/type :buy-flavour
 :data         {:truck {:truck-id … :stock {…} :location … :takings …}
                :flavour "vanilla"}}
```

The second ships a copy of state the handler is about to re-read anyway — state that was already stale when it left, and that the sender has no authority over. A command says *what to do*; it does not get to assert what is currently true.

### Symmetry, and where the envelope stops

Side by side, the request and the fact it produces:

```clojure
;; Request: please do this
{:command/type :buy-flavour
 :data         {:flavour "vanilla"}}

;; Fact: this happened
{:event/type :flavour-sold
 :data       {:flavour "vanilla"}}
```

Almost identical, and deliberately so. Strip the key naming the shape and the two maps are *equal* — the tests assert exactly that. Which is [lab1](../lab1#two-scoping-notes)'s warning from the other side: the shape won't tell you which one you're holding, so the naming discipline above is carrying the whole load.

`:data` is the information that constitutes the request, or the fact — not a blob in transit, for the reason [lab1](../lab1#why-data-and-not-payload) gives.

When either a command or an event crosses a module boundary as a transport message, *that* is where an outer message envelope appears with `:message/id` and `:payload` ([lab3](../lab3)). Correlation and causation are different: they may be propagated on transport messages and on the internal command/event envelopes that participate in one conversation ([lab11](../lab11)). They are metadata about the request or fact, never part of `:data`.

---

## The arrow between them

Something has to stand between the request and the fact, and decide:

```text
BuyFlavour
    ↓
  decide
    ↓
FlavourSold
```

In Clojure:

```clojure
(decide
  {:command/type :buy-flavour
   :data {:flavour "vanilla"}}

  current-state)

;; =>
[{:event/type :flavour-sold
  :data {:flavour "vanilla"}}]
```

Two details in that signature are worth noticing now, even though `decide` isn't implemented until [lab8](../lab8).

It takes **state**, because whether this sale is allowed depends on what has already happened — the context-dependent half of the rejection split above. And it returns a **vector**: one command may produce no events (it was refused), one event, or several. The plural is the general case; a single event is just the common one ([lab5](../lab5) counts).

## What's next

The command comes from outside the domain, and the event stays inside it. But other modules need to hear about what happened, and the moment a fact crosses that boundary it needs a third shape — the integration message, in [lab3](../lab3).

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
