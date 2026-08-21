# Lab 24: who is asking

[Lab 23](../lab23) gave the truck an HTTP surface, and left every endpoint open to anyone who could reach the port. This lab closes it — and the interesting part is not the closing. It is that **authorisation turns out to be four different things**, and they belong in four places this repository has already built.

```bash
bb demo
```
```text
  1. Nobody is anybody yet.
     401  no token                           unauthenticated

  3. Three ways to be told no, and they are three different things.
     403  Rudi sells — wrong role (RBAC)     your role does not permit this command
     403  Sam sells — wrong truck (ABAC)     Not this truck's driver
     400  Sam claims to be Dana in the body  {:data {:actor ["disallowed key"]}}
```

## You never write an identity provider

So this lab does not. It runs [`mock-oauth2-server`](https://github.com/navikt/mock-oauth2-server) — a real OIDC provider that happens to be a test double, with discovery, JWKS, an authorization endpoint and RSA signing — and every line written here is *driving* it.

Which puts it somewhere specific:

```text
src/     the application            no identity provider, no token library beyond the edge
dev/     mock_idp.clj, demo, serve  the provider, and the two entry points that need one
```

**An identity provider is a dependency of your tests, never of your application.** A fitness test asserts that nothing under `src/` names the library, and that the main `:deps` map does not carry it — it appears only in the aliases, beside `dev` on the classpath.

The provider is not in the system map either, and `system.clj` says why:

> If those two strings are all your application knows about your provider, swapping Keycloak for Entra ID is a configuration change. If your provider is in your system map, it is a project.

The two strings are a discovery URL and an issuer.

## The provider is on both sides of the hexagon

This is the clearest example in the repository of what driving and driven actually mean, and it is one external system:

```text
   token  ─────▶  driving adapter  ─────▶  application
                  (auth middleware)

                  driven adapter   ─────▶  the provider's JWKS
                  (adapter/oidc)             ← the key to check it with
```

A token is **pushed at you**. The key that proves it must be **fetched by you**. Same provider, same protocol on the wire, opposite arrows — so `VerificationKeys` is a driven port and the middleware is a driving adapter, and neither fact is about HTTP.

Note the port's shape. It is `verification-key`, not `authenticate`:

> A port called `authenticate` would have moved the decision to the far side of the boundary, where you cannot read it.

It asks the outside world for the one thing only the outside world has. What the key *proves* is decided in `adapter/auth.clj`, in the open.

## Authorisation is four things

ADR-0020 from this repository's archive names four layers. They land in four places, and the split is the lab:

| layer | question | where | built in |
|---|---|---|---|
| **RBAC** | may this role issue this kind of command? | `authority.clj`, at the door | [22](../lab22) |
| **ABAC** | may *this* user act on *this* thing? | `core/truck.clj`, inside `decide` | [8](../lab8) |
| **field-level** | what may this role see? | the query adapter | [9](../lab9) |
| **row-level** | which rows may this role see? | the query adapter | [9](../lab9) |

Which is [lab 2](../lab2)'s distinction, one more time. *May a driver sell?* needs no state and is answered at the door. *May **this** driver sell from **this** truck?* needs the stream, so it is answered by `decide` — and the test for whether something is a business rule is unchanged since lab 2: **can the answer change without the command changing?**

Both refusals reach the client as 403. They are decided in different files, for different reasons, and one of them is a fold away from the events.

## The two gates have different reachability, and that is the point

The RBAC gate guards the door. `app/react` does not use the door — a policy issues commands from inside ([lab 10](../lab10)), so nothing consults `authority` and nothing should, because a policy is not a person and holds no roles.

```clojure
(is (false? (authority/permits? #{} :load-truck)))   ; the gate would refuse it
;; and the restock happens anyway, because the reactor is not a caller
```

So a system relying on edge RBAC alone has an unguarded interior. That is not a flaw to fix at the edge; it is the reason the second layer exists. `decide` is on the only path there is, so a rule there holds for the reactor, a queue consumer, a migration and a REPL alike — and a test proves the ownership check catches a command the door never saw.

## Authority does not propagate

The one that took longest to see, and my favourite thing in the lab.

When a customer buys the last cone, [lab 11](../lab11)'s correlation id runs the whole length of the conversation, and the causation id points back at the depletion. The restock is genuinely *part of* what that customer did.

They did not authorise it.

```clojure
:command/actor {:type "system" :id "restock-when-depleted"}
```

So the actor is **stamped, not inherited**. Copying the triggering user onto a command they never issued writes a false record — [lab 1](../lab1): *a process manager is not a person* — and quietly hands every user the authority of every rule their actions happen to trigger.

> Correlation answers *what was this part of*. The actor answers *who is answerable for it*. Different questions, and only one of them is inherited.

The stream shows the handover:

```text
v3   flavour-sold     user   USR-83721
v4   flavour-sold     user   USR-83721
v5   stock-depleted   user   USR-83721
v6   truck-loaded     system restock-when-depleted
```

## Two tokens, and a property you cannot have twice

| | form | revocable? | lifetime |
|---|---|---|---|
| **access token** | a signed JWT | **no** | minutes |
| **refresh token** | an opaque string | **yes** | weeks |

An access token is a **value** — self-contained, verifiable by anyone holding the public key, and therefore impossible to recall. Nothing the issuer does stops a valid signature from validating. Its short life is the *price* of that property, not a detail.

A refresh token is a **reference** — meaningless without the issuer, and therefore revocable. That is why it is the one allowed to live for weeks.

You cannot have both properties in one token, and deciding which token gets which is the whole design. It is [lab 1](../lab1)'s Hickey note in an unexpected place: a value cannot be recalled, and that is what makes it a value.

## `kid` may be read before verification. `alg` may not

Both live in the same unverified header, so the distinction looks arbitrary:

- **`kid`** chooses which key to *try*. Choose wrong and verification fails; the attacker has wasted a lookup.
- **`alg`** chooses *how* to verify. Trust it and the attacker picks the algorithm you check with — say `alg: none` and there is nothing to check, or say `HS256` and a naive verifier treats the RSA **public** key as an HMAC secret, a secret you publish in your JWKS.

> A value from an unverified token may select an input to verification, never the rule of verification.

`{:alg :rs256}` in `adapter/auth.clj` is stated by us and read from nowhere. A test forges an `alg: none` token asserting `roles: ["depot"]` and watches it die — and asserts that the literal is still in the source, so deleting it fails the build.

This is also the reason to use a library rather than the JDK primitives directly. JWT has a decade of CVEs in it, and buddy-sign requires you to name the algorithm rather than offering to read it.

## Five status codes now

| code | meaning | from |
|---|---|---|
| **400** | malformed — the schema refused it | [22](../lab22) |
| **401** | I do not know who you are | this lab |
| **403** | I know, and no | this lab |
| **409** | the stream moved under you | [7](../lab7) |
| **422** | well-formed, permitted, and the domain said no | [2](../lab2), [8](../lab8) |

**401 says try again with a better token. 403 says a better token will not help.** That difference is worth as much as lab 23's 400-versus-422: a client that cannot tell them apart either retries what can never work, or gives up on what one refresh would have fixed. 401 carries a `WWW-Authenticate` challenge, and says `token expired` when that is why.

The middleware is split in two to make the same distinction structurally. `authenticate` **annotates and never rejects**; `require-authentication` **fails closed**. Whether a request carries a valid token is a *fact*; whether an endpoint demands one is a *policy*, and `/health` wants the first without the second.

## Lab 1's warning, finally testable

Lab 1 said it when nothing could disobey:

> Store an opaque actor id. Never JWTs, tokens, or credentials.

Twenty-three labs had no token to be tempted by. Now there is one, and a test greps every recorded event for it:

```clojure
(is (nil? (re-find #"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+" text))
    "nothing JWT-shaped anywhere in the stream")
```

A bearer credential in append-only storage can never be revoked, proves only that somebody once pasted a string, and drags a bundle of personal claims into the one store designed to resist deletion — the store [lab 15](../lab15) had to build crypto-shredding for. What is kept instead is the `sub`, and nothing else.

## The closed schema turned out to be a security control

ADR-0020 is firm: *roles are extracted from the trusted OIDC claims — never from the request body.*

Two things enforce it here and neither is a check anybody wrote. `intake` builds `:command/actor` from the verified principal and has no path that reads it from the message. And `:data` is `{:closed true}`, so a body that tries is rejected before anything looks at it:

```console
POST /v1/sales  {"flavour":"vanilla","actor":{"id":"USR-83721"}}
400  {:data {:actor ["disallowed key"]}}
```

[Lab 22](../lab22) closed those maps because *"an unexpected key is a bug or an attack."* This is the lab where the second half of that sentence gets a test.

## Authorise, then validate

Not the obvious order, and deliberate:

```text
1. may this caller issue this KIND of command?   roles      — needs nothing fetched
2. is this a well-formed command at all?         the schema — needs the message
3. should it happen, given what is true?         decide     — needs the stream
```

Each gate needs strictly more than the last. Putting roles first costs nothing, because **the command type comes from the route, not the body** — and it means a caller with no permission cannot map your schema by watching which malformed bodies come back 400. A test asserts a forbidden caller gets byte-identical answers for a good body and a garbage one.

## The fourth time JSON ate a keyword, and the first time it got fixed

Writing this, the Postgres half of the suite failed where the in-memory half passed:

```clojure
{:type :user}   ; in memory
{:type "user"}  ; out of JSONB
```

[Lab 19](../lab19) found this and fixed `:data` with a hand-maintained list of field names. [Lab 22](../lab22) replaced the list with per-event-type schemas and called it derived rather than remembered. Both were about `:data`, because `:metadata` held only a causation id — a uuid, which JSON happens not to damage. Adding an actor put a keyword in there and the same boundary took the same bite.

My reflex was the fourth patch: a `Metadata` schema, decoded like `:data`. It worked. It was the wrong repair, and [andfadeev/clojure-event-sourcing](https://github.com/andfadeev/clojure-event-sourcing) shows why — that codebase has this problem **zero** times, and calls `keyword` exactly twice:

```clojure
(defmulti apply-event (fn [_ event] (mapv keyword [(:aggregate_type event) (:type event)])))
(defn- row->resource [row] (-> row (update :payload <-jsonb) (update :type keyword)))
```

Both are **discriminators**, both live in their own `TEXT` column rather than inside the JSON, and both are coerced at the point the code branches on them. Everything else is a string all the way down — including in the Malli schema, where the enums read `[:enum "pending" "paid" "dispatched"]` rather than the keywords a Clojure programmer reaches for by reflex.

So the rule is not *coerce carefully on the way out*. It is:

> **Do not put a keyword in a stream.** A keyword is a program symbol; a stored fact is data. The only keyword worth persisting is one the code dispatches on, and that one belongs in a column of its own.

Three labs of this repository fixed the symptom. The actor is `{:type "user" :id "USR-83721"}` now, `decode-metadata` is deleted, and `schema/event.clj` is shorter than it was.

That rule then reached back through the whole sequence. Every lab from 1 to 24 now writes `"vanilla"` rather than `:vanilla`, [lab19](../lab19)'s coercion list and [lab21](../lab21)'s copy of it are deleted, and [lab13](../lab13) — whose corpus may never be edited — gained a genuine **v4** on its upcast ladder, because the events already written cannot be corrected and a reader has to tolerate both.

And the check that replaces all of it is a **property**, not a rule per field:

```clojure
(is (= actor (json/read-str (json/write-str actor) :key-fn keyword))
    "a keyword here would not come back as one")
```

That fails in memory as loudly as against Postgres, so the next keyword anybody adds to metadata is caught without Docker and without a fourth investigation.

## What a mock cannot prove

The caveat that has to be here, because the lab would otherwise imply more than it earned. **A mock proves your verification logic, not your integration.** Concretely, two places this double stops resembling a real provider — both recorded in tests rather than in prose alone:

**It gives every token the same claims.** Its id token therefore carries the API's audience, and our API accepts it. Against a real provider the id token carries the *client's* audience and the `aud` check rejects it — so the classic OIDC mistake this lab warns about is exactly the one the double cannot reproduce. There is a test asserting the double's behaviour, so the day it improves, the note above fails loudly instead of going stale.

**It does not rotate refresh tokens.** Refreshing returns the same token and presenting a retired one succeeds. RFC 9700 §4.14.2 requires the opposite: rotate on every use, and if a retired token comes back, revoke the whole family, because two parties holding one chain has no innocent explanation. That is a *provider's* job, this one does not do it, and so this lab does not claim to demonstrate it.

Real providers also differ on claim shapes, on clock-skew tolerance, on how roles are nested, and on when they rotate keys. Every test here would pass against a provider you could not actually log in to.

## Testing time without spending it

[Lab 21](../lab21) made `now` a port and demonstrated it with a clock held still — enough for reproducible timestamps. Token expiry needs the other half: time passing, on demand, without passing.

```clojure
(is (some? (:principal (verdict deps token))))   ; valid now
(clock/advance! clock 600)
(is (= :exp (:failure (verdict deps token))))    ; and not ten minutes on
```

Five minutes of validity, tested in no time at all. That is the invoice for lab 21 being settled.

Exactly one test sleeps, for two and a half seconds, and the reason is worth stating: **the held clock proves our verifier honours `exp`; only real time proves the provider issues a token that actually stops working.** Then it refreshes and the call succeeds — the loop a client really runs.

## The checks

`architecture_test.clj` gains five rules, and the two that took a second attempt are the interesting ones:

```
core/truck.clj uses token — the core decides on values, not on credentials
```

That check first failed on the core's own docstring, which mentions tokens precisely to say it has none — the same trap lab 23 hit with `app.clj` naming adapters to explain that it uses none. It now strips comments and string literals before grepping, and `:not-authorised` stays legal: the core may say *why* it refused, and may not know what a bearer token is.

The others: a token library may appear in `auth.clj` and `oidc.clj` and nowhere else; the core never requires the permission table; `app.clj` requires neither the authenticator nor the roles; and nothing under `src/` names the mock provider.

## Deferred

The **authorization-code redirect with PKCE** driven from a browser, and the **BFF** pattern with httpOnly cookies that the archive's roadmap wants — both are properties of the *client*, and this lab is about the resource server. Refresh-token rotation with reuse detection. Row-level security, which needs a second truck. Token introspection and revocation. Relationship-based authorisation of the OpenFGA kind, which is where ownership rules go when there are fifty of them rather than one.

## What's next

The counterweight, which the sequence has been missing since lab 1 and which is now overdue.

Everything in labs 21 to 24 — the ubiquitous language, the intentful endpoints, `decide`, the ports, all four layers of authorisation above — is **independent of event sourcing**. A lab that keeps every one of them and swaps the store for one holding current state would change `app.clj` by about three lines, and nothing else. Two suites: behavioural, which passes against both, and historical, which does not — and the failing suite is the entire value proposition, stated as tests rather than claims.

Twenty-four labs of *how*, and no plain statement yet that most systems should keep the model and skip the store.

## Running it

```bash
bb demo     # the whole thing, including a login and an expiry, no Docker
bb serve    # two servers: the truck on :3000, the provider on its own port
bb test     # 86 tests; the Postgres half needs Docker
```
