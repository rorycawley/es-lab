# Lab 3: an integration message

This lab explores the integration event message — the third and last member of the vocabulary built up across [lab1](../lab1) (domain event) and [lab2](../lab2) (command).

We now have three shapes, side by side:

```clojure
;; Request: please do this
{:command/type :buy-flavour
 :data         {:flavour :vanilla}}

;; Domain fact: this happened inside the domain
{:event/type :flavour-sold
 :data       {:flavour :vanilla}}

;; Integration message: tell another module/system this happened
{:message/type :flavour-sold
 :payload      {:flavour :vanilla}}
```

Once you add the infrastructure envelope, it could become:

```clojure
(def flavour-sold-vanilla-message
  {:message/id   #uuid "7f2678a4-2bd3-4f8e-9a87-7ce7607b1d37"
   :message/type :flavour-sold
   :payload      {:flavour :vanilla}
   :metadata     {:correlation-id #uuid "cc79c083-c1d0-45a5-b18f-5079a3720901"
                  :causation-id   #uuid "31dd15c7-63e4-48ef-a751-12d971e95acc"}})
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

The important semantic distinction is that the **domain event belongs to the domain model**, while the **integration message is a contract sent across a boundary**.

They may initially contain identical information:

```clojure
;; Domain event
{:event/type :flavour-sold
 :data {:flavour :vanilla}}

;; Integration event
{:message/type :flavour-sold
 :payload {:flavour :vanilla}}
```

but they should not be assumed to remain identical forever. The integration message is free to expose only what other modules need — it can drop fields the domain event carries, reshape others, or version independently as the domain model evolves underneath it. That's also why it, and not the domain event, is where `:message/id`, correlation IDs, and causation IDs belong: those are concerns of moving a fact across a boundary, not of the fact itself.
