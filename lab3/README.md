# Lab 3: an integration message

This lab explores the integration message — the third and last member of the vocabulary built up across [lab1](../lab1) (domain event) and [lab2](../lab2) (command).

## Three shapes, side by side

```clojure
;; Request: please do this
{:command/type :buy-flavour
 :data         {:flavour "vanilla"}}

;; Domain fact: this happened inside the domain
{:event/type :flavour-sold
 :data       {:flavour "vanilla"}}

;; Integration message: tell another module/system this happened
{:message/type :flavour-sold
 :payload      {:flavour "vanilla"}}
```

So the clean vocabulary is:

```text
COMMAND
{:command/type ...
 :data ...}

DOMAIN EVENT
{:event/type ...
 :data ...}

INTEGRATION MESSAGE
{:message/type ...
 :payload ...}
```

Three keys, three roles. `:data` is the information that constitutes a command or event; `:payload` is what an outer integration message carries across a boundary; `:metadata` is operational context around any of them. A command can itself arrive over transport, but once decoded its request body is still `:data`; the transport wrapper owns `:payload`.

## Why not just publish the domain event

The important semantic distinction is that the **domain event belongs to the domain model**, while the **integration message is a contract sent across a boundary**.

They may initially contain identical information:

```clojure
;; Domain event
{:event/type :flavour-sold
 :data {:flavour "vanilla"}}

;; Integration message
{:message/type :flavour-sold
 :payload {:flavour "vanilla"}}
```

but they should not be assumed to remain identical forever. The integration message exposes only what other modules need — it can drop fields the domain event carries, reshape others, or version independently as the domain model evolves underneath it.

That freedom is the entire reason for the second shape. Publishing the domain event directly makes every consumer a hostage of the domain model: rename a field to say what the domain now means, and something in another module breaks. Keeping them separate means the domain can be refactored without silently changing the contract. It does not make a breaking contract change cheap: [lab13](../lab13#two-notes) requires publishing both versions during a transition because you cannot run an upcaster in somebody else's process.

## The infrastructure envelope

Once you add the infrastructure envelope, it could become:

```clojure
(def flavour-sold-vanilla-message
  {:message/id   #uuid "7f2678a4-2bd3-4f8e-9a87-7ce7607b1d37"
   :message/type :flavour-sold
   :payload      {:fact-id "018f7a3e-0000-7000-8000-000000000001"
                  :flavour "vanilla"}
   :metadata     {:correlation-id #uuid "cc79c083-c1d0-45a5-b18f-5079a3720901"
                  :causation-id   #uuid "31dd15c7-63e4-48ef-a751-12d971e95acc"}})
```

`:message/id` belongs only to this transport envelope: it identifies a send, not the fact being sent. Correlation and causation are different. They describe the chain that produced a request or fact, so they may be propagated through internal command/event envelopes as well as copied into an integration message ([lab11](../lab11)). They belong in metadata, never in the domain event's `:data`.

## Identity: the message id is not the event id

That last claim needs one refinement, because "the domain event has no id" would be the wrong reading of it. The domain event does have an identity — [lab4](../lab4) gives it one — and it is a different identity from the message's.

```text
:event/id     identifies the FACT       — one sale
:message/id   identifies the DELIVERY   — one attempt to tell someone about it
```

The relationship is one-to-many. A publisher that publishes one sale three times has **one** event id and **three** message ids. A broker redelivering the identical envelope is the other case: same event id and same message id. So the two ids answer different questions, and using the wrong one has a visible consequence:

- Deduplicating on `:message/id` catches only a *redelivery of the same message* — the broker handing you the identical envelope twice. It does not catch the producer republishing the same fact in a new envelope, because that envelope is genuinely a different message.
- Deduplicating on the fact id inside the payload catches all of it: however many envelopes arrive, the consumer recognises that it has already processed this sale.

That second check supplies a stable deduplication key; it is not an exactly-once guarantee by itself. One fact may legitimately be handled once by the customer app and once by purchasing, so [lab20's inbox](../lab20#the-inbox-and-what-lab-12s-consumer-could-not-do) keys on `(recipient, fact-id)` and records it in the same transaction as the business effect.

So a well-formed integration message carries both — its own delivery identity, and the identity of the fact it is delivering:

```clojure
{:message/id   #uuid "7f2678a4-2bd3-4f8e-9a87-7ce7607b1d37"   ; this delivery
 :message/type :flavour-sold
 :payload      {:fact-id "018f7a3e-0000-7000-8000-000000000001"  ; the fact
                :flavour "vanilla"}
 :metadata     {:correlation-id #uuid "cc79c083-c1d0-45a5-b18f-5079a3720901"
                :causation-id   #uuid "31dd15c7-63e4-48ef-a751-12d971e95acc"}}
```

The event id crosses the boundary *inside the payload*, as data — because from the receiving module's point of view it is an opaque, stable handle on someone else's fact. [Lab20](../lab20#serialisation-one-level-up) gives the wire field the unnamespaced name `:fact-id`, because JSON drops the namespace from `:event/id`; [lab24](../lab24) also serialises the UUID as a string. Those are contract representations of the same identity. It is not part of the message envelope, because that envelope is about this hop.

Correlation and causation ids sit in `:metadata` and describe the *chain*: correlation groups everything stemming from one originating request, causation names the immediate predecessor. Unlike `:message/id`, they can survive across hops and appear on the internal messages participating in the same conversation.

The refined rule, then:

```text
the fact       has an identity that never changes           :event/id
the delivery   has an identity per hop, per send            :message/id
the chain      has identities describing how we got here    :metadata
```

## What's next

The vocabulary is complete: request, fact, and the contract that carries the fact elsewhere. But the section above leans on an `:event/id` that the domain event doesn't have yet — [lab4](../lab4) gives it one, and asks where identity comes from.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
