# Lab 23: intentful endpoints

[Lab 21](../lab21) built the driven side of the hexagon — a store, an outbox, a clock. [Lab 22](../lab22) added a driving edge, but one you had to call from Clojure. This lab gives it HTTP, and the thing to notice is how little changes below the adapter.

```bash
bb serve
```
```console
$ curl -s localhost:3000/v1/restocks -d '{"flavour":"vanilla","quantity":2}'
{"recorded":[{"type":"truck-loaded","version":1,"data":{"flavour":"vanilla","quantity":2}}]}

$ curl -s localhost:3000/v1/sales -d '{"flavour":"vanilla"}'
{"recorded":[{"type":"flavour-sold","version":2,"data":{"flavour":"vanilla"}}]}

$ curl -s localhost:3000/v1/stock
{"stock":{"vanilla":1}}
```

## Driving and driven describe a relationship, not a technology

The distinction that makes the hexagon work, and the one most often blurred:

```text
HTTP request  ─────▶  driving adapter  ─────▶  application  ─────▶  driven adapter  ─────▶  Postgres
                      (reitit handler)                              (event store)

                                             application  ─────▶  driven adapter  ─────▶  Stripe
                                                                   (an HTTP client)
```

**HTTP appears on both sides.** Same protocol, possibly the same library, opposite ends: one calls your application, the other is called *by* it. Driving and driven describe the direction of the arrow, not the wire.

Which is why this lab renames `port.clj` to **`port/driven.clj`**, and why there is no `port/driving.clj`:

> A protocol buys substitutability of the thing **behind** a port. On the driven side that is exactly what varies — lab 21 runs one suite against a map and against Postgres, so `EventStore` earns a protocol. On the driving side the thing behind the port is your application, and there is one of those. What varies is **who calls it**, and a caller needs no protocol to call a function.

|  | one | many |
|---|---|---|
| **driven** | port | implementations — memory, Postgres |
| **driving** | implementation — the application | callers — HTTP, the demo, every test |

## Name the act, not the entity

No `/api/commands/` prefix. Stripe's shape, and Stripe's underlying rule:

```text
POST /v1/sales          a sale happened
POST /v1/restocks       the truck was restocked
GET  /v1/stock          a query
GET  /health            operational
```

`POST /v1/refunds` creates a refund, and a refund is **an act**. `PUT /v1/charges/{id}` mutates **a thing**, and carries no intent at all — which is ADR-0016's argument in one line:

> A `PUT` that changes status from `submitted` to `withdrawn` looks identical at the HTTP level to any other `PUT`.

Everything this repository has built dies at that `PUT`. The imperative name ([lab2](../lab2)), the refusal distinguishable from malformed input ([lab22](../lab22)), the audit entry that records intent rather than a field diff — none of it survives a verb that means "some fields changed".

The intent becomes a noun by naming the **act**, not the thing acted upon.

## Status codes are lab 2's two columns

Where the repository's central distinction finally reaches a client:

| code | meaning | from |
|---|---|---|
| **200** | accepted, here are the facts | |
| **400** | malformed — the schema refused it; the domain never saw it | [lab22](../lab22) |
| **422** | well-formed, and the domain said no | [lab2](../lab2), [lab8](../lab8) |
| **409** | the stream moved under you; re-read and retry | [lab7](../lab7) |

```console
$ curl -o /dev/null -w '%{http_code}\n' :3000/v1/sales -d '{"flavour":"tarmac"}'
400
$ curl -o /dev/null -w '%{http_code}\n' :3000/v1/sales -d '{"flavour":"chocolate"}'
422
```

Most APIs collapse those into one number, and in doing so tell the client nothing useful. **A 400 will never succeed unchanged. A 422 might, tomorrow.** A test asserts exactly that: the 422 becomes a 200 once the truck is restocked, and the 400 stays a 400 forever.

409 is optimistic concurrency arriving at a client, with the advice lab 7 gave — read again, decide again — now an HTTP contract.

## A command returns facts, not the resource

```json
{"recorded":[{"type":"flavour-sold","version":2,"data":{"flavour":"vanilla"}}]}
```

Not the truck. Returning the mutated entity is a REST habit that pulls you straight back to resource thinking; what happened is the command's business, and current state is a **query's**. That's CQRS showing up in a response body, and there's a test asserting the command response carries no stock figure.

## Tests are driving adapters

Which is not a figure of speech. Count what actually drives this application:

| driving adapter | enters at | why there |
|---|---|---|
| `adapter/http.clj` | `intake/submit` | a socket |
| `demo.clj` | both `intake/submit` and `app/handle` | telling a story |
| `test/…/http_test.clj` | `http/handler` | status codes, routes, the wire |
| `test/…/intake_test.clj` | `intake/submit` | malformed versus refused |
| `test/…/app_test.clj` | `app/handle` | orchestration, against two stores |

Five drivers, three entry points. **Choosing where a test drives is choosing what it tests** — and that choice is only available because each level is an ordinary function rather than something you have to stand up.

`app_test.clj` drives beneath validation, so it can run the same suite against a map and against Postgres without a schema in the way. `intake_test.clj` drives at the validation edge, because that is where the two rejections live. `http_test.clj` drives the transport, because 400-versus-422 is a transport concern.

None of them mocks anything. A mock is what you reach for when a layer cannot be entered directly; here every layer can.

## A ring handler is a function from a map to a map

Which means the whole web layer is testable by calling it:

```clojure
(handler {:request-method :post :uri "/v1/sales" :body …})
```

Every test in `http_test.clj` does that — no socket, no port, no HTTP client. **Exactly one** starts Jetty, on port 0, to prove the wiring exists. If testing your web layer needs a running server, the layer is doing too much.

## The checks

`architecture_test.clj` grows two rules, and both were verified by breaking them:

```
app.clj requires reitit — HTTP is an adapter, not a dependency
```

Ring and reitit may appear in `adapter/http.clj` and nowhere else. `ring.adapter.jetty` may appear in `system.clj` and nowhere else, because a web server has a lifecycle and Component owns lifecycles.

And the one I most wanted, in `http_test.clj` — **bidirectional**:

- every `/v1/<act>` command route maps to a command type in the schema registry
- **and every command type has a route**

Add a command without exposing it, or expose one that doesn't exist, and the build fails. The endpoint list and the domain's command vocabulary become the same list, enforced rather than remembered.

## The wire and the domain now agree

Writing this lab, the first request came back **400 on a perfectly good body**. The domain wanted `:vanilla` and `{"flavour":"vanilla"}` arrives as a string, so every well-formed request was malformed.

The fix at the time was [lab22](../lab22)'s decoder, pointed the other way — coerce the body before validating it. The fix now is that there is nothing to coerce: a flavour is a string on the wire, in the command, in the event and in the store, because [lab19](../lab19) stopped writing keywords into streams and the rule reached back through every lab.

```clojure
(let [wire {:command/type :buy-flavour :data {:flavour "vanilla"}}]
  (is (nil? (command/validate wire)))    ; valid exactly as it arrived
  (is (= wire (command/decode wire))))   ; and decoding is the identity
```

The decode step stays in the pipeline anyway:

```clojure
(let [command (schema/decode (->command ids message))]   ; decode, then
  (if-let [problems (schema/validate command)] …))       ; validate
```

It costs one line, it is where a coercion belongs the day a command grows a field JSON does damage, and the **ordering** it demonstrates is still worth having: validating the wire form rejects every well-formed request; decoding without validating coerces nonsense into plausible values.

### One place the loss is still real

`GET /v1/stock` returns a map **keyed by flavour**:

```json
{"stock":{"vanilla":1}}
```

Perfectly ordinary JSON, and a trap for a Clojure client, because the idiomatic `:key-fn keyword` cannot tell a field name from a datum:

```clojure
(json/read-str body :key-fn keyword)   ; => {:stock {:vanilla 1}}
```

`"stock"` is a field name and wants keywordizing. `"vanilla"` is data and does not. One blanket setting cannot do both — which is the same rule as the one about values, one level up, and an argument for not keying a map by domain data when it crosses a boundary. A test asserts both readings so the hazard is visible rather than discovered.

## GET for queries, against the archive's own rule

The archive's `ROADMAP.md` is explicit:

> There are no `GET`, `PUT`, `PATCH`, or `DELETE` business endpoints in es-lab projects… **POST with JSON for both commands and queries.**

This lab departs from that, and the argument deserves both sides.

**For POST-only** (the archive): the URL is always the operation name, regardless of kind. Structured filters travel in a body without URL-encoding or length limits. Auth, audit and rate-limiting apply uniformly, with no "except for reads" branch.

**For GET on queries** (this lab): a query *is* safe and idempotent, and the method is the standard place to say so — to caches, proxies, crawlers and the next developer. Suppressing that is discarding information to preserve a rule. And the risk the rule guards against, a `GET` that mutates, is exactly what the bidirectional route check makes impossible here: a command may only be reached by `POST`, asserted.

The rule earns its keep when queries carry complex filters. For `GET /v1/stock`, it costs more than it protects.

## Deferred

OpenAPI generation, content negotiation beyond JSON, and API versioning past the `/v1` prefix. The archive roadmaps each separately.

## What's next

Every endpoint above is open to anyone who can reach the port. [Lab24](../lab24) closes them, with a real OIDC provider and a bearer token — and finds that **authorisation is four different things**, one of which needs state and therefore belongs in `decide` rather than at this edge.

It also adds the pair of status codes this table is missing. 401 says *try again with a better token*; 403 says *a better token will not help* — which is the same shape of information as 400-versus-422, one layer further out.

## Running it

```bash
bb serve    # HTTP on :3000, in memory, no Docker
bb demo     # the same system, printing rather than listening
bb test     # 48 tests; the Postgres half needs Docker
```
