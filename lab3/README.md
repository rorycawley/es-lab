# Lab 3: an integration message

This lab explores the integration event message — the third and last member of the vocabulary built up across [lab1](../lab1) (domain event) and [lab2](../lab2) (command).

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

Three keys, three worlds. `:data` for the two shapes that live inside the domain; `:payload` for the one that leaves it, because now something really is being carried somewhere — which is what the word means, and why [lab1](../lab1) refused it for the domain event.

## Why not just publish the domain event

The important semantic distinction is that the **domain event belongs to the domain model**, while the **integration message is a contract sent across a boundary**.

They may initially contain identical information:

```clojure
;; Domain event
{:event/type :flavour-sold
 :data {:flavour "vanilla"}}

;; Integration event
{:message/type :flavour-sold
 :payload {:flavour "vanilla"}}
```

but they should not be assumed to remain identical forever. The integration message is free to expose only what other modules need — it can drop fields the domain event carries, reshape others, or version independently as the domain model evolves underneath it.

That freedom is the entire reason for the second shape. Publishing the domain event directly makes every consumer a hostage of the domain model: rename a field to say what the domain now means, and something in another module breaks. Keeping them separate means the domain can be refactored freely, and the contract changes only when someone decides it should.

## The infrastructure envelope

Once you add the infrastructure envelope, it could become:

```clojure
(def flavour-sold-vanilla-message
  {:message/id   #uuid "7f2678a4-2bd3-4f8e-9a87-7ce7607b1d37"
   :message/type :flavour-sold
   :payload      {:flavour "vanilla"}
   :metadata     {:correlation-id #uuid "cc79c083-c1d0-45a5-b18f-5079a3720901"
                  :causation-id   #uuid "31dd15c7-63e4-48ef-a751-12d971e95acc"}})
```

This is also where `:message/id`, correlation IDs, and causation IDs belong, rather than on the domain event: those are concerns of moving a fact across a boundary, not of the fact itself.

## Identity: the message id is not the event id

That last claim needs one refinement, because "the domain event has no id" would be the wrong reading of it. The domain event does have an identity — [lab4](../lab4) gives it one — and it is a different identity from the message's.

```text
:event/id     identifies the FACT       — one sale
:message/id   identifies the DELIVERY   — one attempt to tell someone about it
```

The relationship is one-to-many. A single sale, published once and then retried twice after a broker hiccup, has **one** event id and **three** message ids. So the two ids answer different questions, and using the wrong one has a visible consequence:

- Deduplicating on `:message/id` catches only a *redelivery of the same message* — the broker handing you the identical envelope twice. It does not catch the producer republishing the same fact in a new envelope, because that envelope is genuinely a different message.
- Deduplicating on the event id inside the payload catches all of it: however many envelopes arrive, the consumer recognises that it has already processed this sale.

So a well-formed integration message carries both — its own delivery identity, and the identity of the fact it is delivering:

```clojure
{:message/id   #uuid "7f2678a4-2bd3-4f8e-9a87-7ce7607b1d37"   ; this delivery
 :message/type :flavour-sold
 :payload      {:event/id #uuid "018f7a3e-0000-7000-8000-000000000001"  ; the fact
                :flavour  "vanilla"}
 :metadata     {:correlation-id #uuid "cc79c083-c1d0-45a5-b18f-5079a3720901"
                :causation-id   #uuid "31dd15c7-63e4-48ef-a751-12d971e95acc"}}
```

The event id crosses the boundary *inside the payload*, as data — because from the receiving module's point of view it is data: an opaque, stable handle on someone else's fact. It is not part of the envelope, because the envelope is about this hop.

Correlation and causation ids sit in `:metadata` and belong to neither the fact nor the delivery, but to the *chain*: correlation groups everything stemming from one originating request, causation names the immediate predecessor. They are the envelope's business, exactly as stated above.

The refined rule, then:

```text
the fact       has an identity that never changes           :event/id
the delivery   has an identity per hop, per attempt         :message/id
the chain      has identities describing how we got here    :metadata
```

## What's next

The vocabulary is complete: request, fact, and the contract that carries the fact elsewhere. But the section above leans on an `:event/id` that the domain event doesn't have yet — [lab4](../lab4) gives it one, and asks where identity comes from.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
