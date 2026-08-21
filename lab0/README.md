# Lab 0: the model

Before there is an event, a stream, a store or a projection, there is a **model** — and if that is wrong, nothing built on top of it can be right.

This lab has no events in it. It is the lab that would exist if the rest of the sequence did not: two business rules about an Ice Cream truck, expressed as functions of values, with no database, no HTTP, no framework and nothing to install. Then the same two rules written the way a framework would have you write them, so the difference is something you can read rather than something you have to take on trust.

```bash
bb test     # 21 tests, no Docker, no configuration, nothing running
```

## A model is a reduction, and the reduction is the point

A model is a conceptual map: a shared understanding of how ideas connect, held in common by the people who know the business and the people writing the code.

A model train is the analogy worth keeping. It is not a small real train — it is a **deliberate reduction**, in which somebody decided which resemblances mattered and threw away the rest. It has no working boiler and no timetable, and that is not a shortcoming. Whoever built it chose which properties to keep based on what the model was for, and the throwing away is what makes it useful.

> Essentially all models are wrong, but some are useful.
> — George E. P. Box

So you do not model the business. You reduce it to the subset of rules needed to answer the question in front of you.

## Which is a criterion, not a slogan

"A model leaves things out" is easy to agree with and useless on its own, because it says nothing about *which* things. So this lab uses a version you can run:

> **An attribute that cannot change any answer is not part of the model.**

Here is everything true about truck IC-2019-A, one morning in August. None of it is invented, and the fleet manager would be annoyed if you lost any of it:

```clojure
{:stock             {"vanilla" 3 "chocolate" 2}
 :registration      "IC-2019-A"
 :paint-colour      "pink"
 :chime-tune        "Greensleeves"
 :tyre-pressure-psi 32
 :odometer-km       84213
 :insurance-renews  #inst "2027-03-01"
 :last-washed       #inst "2026-08-18"
 :freezer-serial    "FZ-88120-B"
 :driver            {:name "Dana" :favourite-radio-station "Lyric FM"}}
```

`model_test.clj` varies each of those across every value the business might plausibly give it — a flat tyre, no insurance, a different driver, no chime at all — and asks every question the model exists to answer after each change.

Not one answer moves. Nine attributes come out, and the truck reduces to:

```clojure
{:stock {"vanilla" 3 "chocolate" 2}}
```

A test asserts the reduced truck and the whole truck answer **identically**. Another asserts the converse, because a criterion that only ever says "leave it out" is not a criterion: change `:stock` and the answers do move, which is precisely what earns it a place.

**What is left out is not lost.** The insurance renewal date still matters to somebody; it is simply not part of deciding whether a cone can be sold. Different question, different model — and forcing both into one object is how a model stops being useful to either.

## The `models/` folder is usually not a model

Open a modern web framework, find the folder called `models`, and look at what is in it. Fields that are a table's columns. A lifecycle that is a row's lifecycle. Methods that read and write.

The framework calls them models. They are a **persistence mechanism wearing the domain's vocabulary**, and this lab contains one so the comparison is concrete rather than rhetorical:

```clojure
(defrecord TruckRow [id registration stock created_at updated_at])
```

Three of those five fields are the mechanism, not the business. No ice cream seller has ever mentioned a surrogate id or an `updated_at`, and `snake_case` is SQL's convention leaking upward into the thing the business believes is its truck.

**This is not a straw man.** `models/truck.clj` is not badly written — it is what every ORM tutorial shows you, and it works. Its store is an atom rather than Postgres so this lab installs nothing, because the objection was never that databases are slow. The objection is what the business rule has been **tied to**, and the tests price it:

| | domain model | persistence model |
|---|---|---|
| ask "can it sell vanilla?" | a map literal | a store, a row, and an id |
| ask it twice | the same answer | different answers — there is a clock in it |
| the rule's name | `sellable?` | it hasn't got one |

That last row is the expensive one, and it surprised me to find it was mechanically checkable:

```clojure
(is (nil? (resolve 'lab0.models.truck/sellable?)))
(is (nil? (resolve 'lab0.models.truck/room-for?)))
```

Neither rule is missing. Both are *in there*, as an `if` in the middle of a method that also writes. But there is nowhere to put a name for them — a predicate that needs a store is not a statement about ice cream — so the rule cannot be pointed at, cannot be tested on its own, and cannot be read back to the person who asked for it.

This is what Hickey means by **complecting**: the *what* of the business and the *how* of the storage, braided together so that touching either means touching both.

## A domain model is the map, written down

The real thing is the codified version of the mental map the domain experts and the developers already share, with the technical detail taken out. It expresses the invariant rules of the business in whatever primitives the language gives you — functions, types, namespaces — and it explicitly declines to know that databases, HTTP requests or message brokers exist.

The whole of this one:

```clojure
(def capacity 40)

(defn sellable?  [truck flavour]  (pos? (stock-of truck flavour)))
(defn room-for?  [truck quantity] (<= (+ (total-stock truck) quantity) capacity))

(defn sell       [truck flavour] …)
(defn load-cones [truck flavour quantity] …)
```

Values in, a value out. Nothing is saved, because **saving is not a domain concept** — nobody who sells ice cream has ever said "and then I persist the truck".

## The advantage: a change costs what the change costs

This is the one that pays for the rest. When the core is a direct reflection of the business's conceptual map, a change in how the business operates produces a code change **of the same nature and scale**.

The business said one sentence in August: *the truck holds forty cones.* It cost one constant and one predicate, in the file the rule is about, and the test needed no setup:

```clojure
(with-redefs [truck/capacity 50]
  (is (true? (truck/room-for? {:stock {"vanilla" 40}} 1))))
```

No migration, no schema change, no routing, no ORM mapping, no fixture. Say the same sentence to `models/truck.clj` and it lands inside a method, between a read and a write, next to a timestamp — and the test for it needs a store, a row and an id first.

That is the architecture allowing the system to evolve as the business does, instead of the business waiting on the machinery.

## The checks

A README claiming "the core has no dependencies" is a wish. `architecture_test.clj` reads the source and fails the build:

```
truck.clj mentions jdbc — the model is about ice cream, not about machinery
truck.clj calls java.util.Date
truck.clj has grown to 41 lines of code
```

The rules: the model requires **nothing at all** (its `:require` list is empty, not short); it names no technical concern from a list including `jdbc`, `http`, `repository` and `dao`; it reaches for no clock, no randomness, no mutation and no output; and it stays small enough to hold in your head, because a model nobody can read is not a shared understanding.

Two notes on getting the check itself right. It strips comments and string literals before grepping, because this file *discusses* HTTP and databases precisely to say it has none — a grep that cannot tell an argument from an import fails on the argument. And I first wrote it lower-casing the source, which silently made the `java.util.Date` and `System/currentTimeMillis` checks unable to match anything. Both were verified by breaking the rule and watching the build go red.

## What's next

A model tells you what is true *now*. It cannot tell you what happened, when, in what order, or who did it — and a truck's stock going from 3 to 2 is a different thing from knowing that a cone was sold.

[Lab1](../lab1) records the fact. It keeps this model exactly as it is and adds one idea on top: a **domain event**, a business fact that has already happened, which cannot be refused. Everything else in the sequence follows from that.

## Running it

```bash
bb test     # 21 tests
bb all      # setup, check, test
```
