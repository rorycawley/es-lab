# Lab 15: deletion

An event store is append-only. A person has a right to erasure. These two facts do not get on, and this lab is about what you actually do.

## Why you can't just delete the event

Not because immutability is elegant — because deleting one breaks invariants earlier labs *assert in tests*.

[Lab7](../lab7) pins stream versions as contiguous `1..n`. Take an event out of the middle and there's a hole:

```clojure
[1 3 4]   ; and every optimistic-concurrency check downstream now has a gap to explain
```

Replay produces a history that never happened. The audit trail that justified building an event store in the first place is gone. And by then the fact has been projected, published ([lab12](../lab12)) and backed up, so the copy you deleted was one of several.

A test shows this breakage directly, so the alternative that follows has something to be better than.

## Two answers, and the boring one is the default

**1. Keep personal data out of the log.** Events reference a subject by id; the description of that subject lives in an ordinary mutable store you can delete from.

This is the right answer most of the time, and the lab makes it structural rather than stating it. A sale carries:

```clojure
{:event/type :flavour-sold
 :data       {:flavour "vanilla" :customer-id "C-123"}}
```

A test pins those data keys at exactly `#{:flavour :customer-id}` — no name, no email, **nothing to erase**. Three sales, a thousand sales, a decade of sales: erasing this customer never has to touch one of them.

**2. Crypto-shredding**, for the residue that won't separate. Some facts genuinely describe a person — there is no version of "a loyalty card was issued to someone" that omits who. So that field is encrypted under a key belonging to that subject, and erasure destroys the key.

In this lab exactly **one event in the whole log** holds personal data, and it's the card issuance. That ratio is the point: separation does the heavy lifting, and shredding handles what's left.

## What shredding leaves the fold holding

```clojure
(read-event vault-with-key    card-event)  ;; => {:name "Aoife Ní Bhriain" …}
(read-event vault-without-key card-event)  ;; => :personal/erased
```

That marker is [lab13](../lab13)'s `:price/unknown` in a different costume, and chosen the same way: **not `nil`, not `""`**. Both are values the field could legitimately have held, so both would let an erasure quietly pass for data. An explicit marker forces every reader to decide what to do about it.

Unsealing happens **at the edge**, exactly where lab13 upcasts, and for the same reason: the domain sees one shape and never learns encryption exists. A fold that had to decrypt would carry the vault through every method forever.

## The identity goes; the facts stay

This is what makes the pattern worth its complexity.

```clojure
(replay-truck stream)                     ;; => {"vanilla" 7}
(replay-truck (read-all shredded stream)) ;; => {"vanilla" 7}
```

Identical. Three cones were sold, and they still were. The card is still known to have been issued, to customer `C-123`, on that date — only *who C-123 was* is gone. Accounting, stock reconciliation and fraud analysis are untouched, because none of them ever needed a name.

Which gives the modelling rule: **seal a subset of `:data`, not the whole of it.** Shred the identity, not the fact.

## Where erasure actually leaks

Destroying the key makes the log unreadable. It does nothing whatsoever to a projection that already materialised the plaintext — that copy was made while the key existed, and it lives in a store nobody encrypted.

```clojure
(name-of (rebuild held    log) "C-123")  ;; => "Aoife Ní Bhriain"
(name-of (rebuild shredded log) "C-123") ;; => :personal/erased
```

Same log. Same code. The only difference is *when the read model was built*.

So erasure is not one operation. It is: destroy the key, **and rebuild every projection that touched the data**, and tell everyone you published to. [Lab9](../lab9) argued that read models are disposable because they hold nothing the events don't; here that stops being a nice property and becomes a compliance requirement. If a projection can't be rebuilt, it is now personal data you cannot erase.

And the third part you cannot enforce. Once an integration message has left ([lab12](../lab12)), the other module has its own copy in its own store, and all you can send is a request. That is [lab14](../lab14)'s pivot wearing different clothes: publication is a point of no return.

## Three things this does not solve

**Key management reintroduces a mutable store.** Crypto-shredding doesn't remove the need for something you can delete from — it shrinks it to one small enough to reason about. The log stays append-only *because the vault doesn't*. Worth saying plainly rather than implying the store became self-sufficient.

**Backups.** A backup taken before the key was destroyed still contains the key. Either key destruction propagates to backups, or backups exclude the key store, or you accept a window and document it. This is the genuinely nasty operational part and no amount of design tidiness removes it.

**Whether any of this satisfies a regulator is a legal question, not an architectural one.** The pattern makes data unreadable without rewriting an append-only store, which is architecturally useful and not a complete solution. It has to be validated against audit obligations, statutory retention, backups, projections, logs and reporting — and some of those will require the opposite of erasure.

## What survives on purpose

Erasure is rarely all-or-nothing. Financial records, fraud investigation and statutory retention routinely outlive a deletion request, and the law generally says so.

So the design question isn't "how do I delete a customer" — it's **which fields are personal data, and which facts must survive regardless**. Getting that wrong in the cautious direction is as bad as the other: a shredded key can't be un-shredded, and a fact you needed for an audit is simply gone.

## What's next

The write side, the read side, publication, evolution, failure and erasure are all covered. What has never been asked is *where the boundary goes* — which events belong in one stream at all. [Lab16](../lab16) builds one domain three ways and measures the difference.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
