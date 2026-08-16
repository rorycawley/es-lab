# Lab 2: a command

This lab explores what a command is, and how it relates to the event from [lab1](../lab1).

A command is a request: *please do this*. An event is a fact: *this happened*. The command we'll model is the request that, if accepted, produces the `flavour-sold` event from lab1 — it's called `buy-flavour`.

```clojure
(def buy-flavour-vanilla-command
  {:command/type :buy-flavour
   :data         {:flavour :vanilla}})
```

So the pair becomes:

```clojure
;; Request: please do this
{:command/type :buy-flavour
 :data         {:flavour :vanilla}}

;; Fact: this happened
{:event/type :flavour-sold
 :data       {:flavour :vanilla}}
```

The naming distinction is important:

```text
COMMAND                         EVENT
────────                        ─────
:buy-flavour                    :flavour-sold
imperative / request            past-tense fact
may be rejected                 has already happened
```

Conceptually:

```text
BuyFlavour
    ↓
  decide
    ↓
FlavourSold
```

And in Clojure:

```clojure
(decide
  {:command/type :buy-flavour
   :data {:flavour :vanilla}}

  current-state)

;; =>
[{:event/type :flavour-sold
  :data {:flavour :vanilla}}]
```

There's a symmetry worth keeping:

```clojure
{:command/type ...
 :data ...}

{:event/type ...
 :data ...}
```

`:data` is the information that constitutes the request, or the fact — not a blob in transit. When either a command or an event crosses a module boundary as a transport message, *that* is where we'd introduce an outer message envelope with `:message/id`, `:payload`, correlation IDs, and so on. Neither of those concerns belongs here.
