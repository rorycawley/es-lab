# SPEC2 - Use-Case 3.0 Requirements Specification

This document describes the shopping-cart requirements as a Use-Case 3.0 model.
It is intentionally written at requirements level: system of interest, actors,
goals, flows, slices and test cases.

"Use-Case 3.0" is this project's own naming for a house variant that extends the
published Use-Case 2.0 method (Jacobson, Spence and Bittner). There is no
separate Use-Case 3.0 standard to consult; the slice states used in section 7
are the Use-Case 2.0 ones.

It deliberately avoids implementation decisions such as programming language,
frameworks, databases, protocols, deployment tools, table names, component
names, ports and adapters, test commands and source-code namespaces. Those details
belong in a use-case realization, architecture document, implementation plan or
test strategy after the requirements are understood.

## 1. System of Interest

### Name

Shopping Cart System

### Purpose

Allow a customer, or an external system acting in a customer journey, to build,
inspect, confirm or cancel a shopping cart while preserving clear cart state
when multiple actors act on the same cart.

### Business Outcome

A customer can reliably express the products and quantities they intend to buy.
When multiple actors use the same cart, the system preserves every accepted
change and clearly rejects any change that is based on an out-of-date cart view.

### Scope

In scope:

- adding product items to a cart
- removing product items from a cart
- viewing current cart contents and status
- confirming a cart
- cancelling a cart
- detecting and reporting stale cart-observation conflicts
- safely retrying any cart-changing command without applying it twice
- exposing a change history when needed for support or audit

Out of scope:

- product search
- product catalogue maintenance
- stock reservation
- payment
- order fulfilment
- delivery
- actor authentication
- authorization rules
- product pricing, price capture and price agreements
- publishing accepted cart changes to external consumers
- localization of display messages

### Trust Boundary

This version may be deployed only behind a trusted boundary that authenticates
callers and authorizes their access to cart-management or support operations.
Those controls are supplied outside the Shopping Cart System. A cart identifier
or observation marker is not a credential and must not be treated as proof that
its holder is allowed to view or change a cart. Direct exposure of this version
to untrusted callers is outside its permitted deployment boundary.

## 2. Actors

| Actor | Description |
|---|---|
| Customer | A person who builds, reviews, confirms or cancels a cart through a user-facing channel. |
| External Cart System | An external system, such as a bot, script or partner commerce system, that manages carts as part of its own customer journey or on behalf of a customer. |
| Support User | A person or system that inspects a cart's accepted change history. |

An actor is a role outside the Shopping Cart System with a goal. It may be a
person or another system; it does not need to use a user interface.

The Customer and External Cart System can both initiate the main cart-management
use cases. This document uses `Cart Manager` as a collective term for either of
these actors unless a flow explicitly says otherwise.

Concurrent behavior is not modeled as a separate actor goal. It is a condition
that can occur when any two cart-managing actors act on the same cart.

## 3. Use-Case Model

The following model shows the useful ways the actors use the Shopping Cart
System. Each use case is named by the actor goal, not by an implementation
operation.

```text
Customer
  -> Manage Cart Contents
  -> View Cart
  -> Confirm Cart
  -> Cancel Cart

External Cart System
  -> Manage Cart Contents
  -> View Cart
  -> Confirm Cart
  -> Cancel Cart

Support User
  -> Review Cart Change History
```

| Use Case ID | Use Case | Primary Actors | Goal | Priority | State |
|---|---|---|---|---|---|
| UC-01 | Manage Cart Contents | Customer, External Cart System | Add or remove product quantities, establishing a cart with the first accepted addition. | Must | Flow Structure Understood |
| UC-02 | View Cart | Customer, External Cart System | See current cart status and contents. | Must | Flow Structure Understood |
| UC-03 | Confirm Cart | Customer, External Cart System | Mark an eligible cart as ready for the next business step. | Must | Flow Structure Understood |
| UC-04 | Cancel Cart | Customer, External Cart System | Abandon a cart so it cannot be changed further. | Must | Flow Structure Understood |
| UC-05 | Review Cart Change History | Support User | Understand accepted changes for a cart. | Must | Flow Structure Understood |

## 4. Supporting Information

### Glossary

| Term | Meaning |
|---|---|
| Cart | A temporary collection of product quantities before checkout or cancellation. |
| Cart Identifier | A system-generated UUID returned when the first product addition creates a cart and supplied by actors in later requests for that cart. |
| Product Identifier | An actor-supplied UUID used as an opaque product reference. This version does not check it against a product catalogue. Product data, including price, is owned outside the cart. |
| Product Item | A product identifier and quantity supplied to the cart. |
| Requested Quantity | The number of units an actor asks to add or remove in one command. It must be a positive whole number no greater than 1000. |
| Held Quantity | The number of units of one product the cart currently holds. It must not exceed 1000. A product whose held quantity reaches zero no longer appears in cart contents. |
| Command Request Identifier | An actor-supplied UUID from one system-wide namespace that identifies one cart-changing command so an accepted command can be retried without being applied twice. |
| Logical Command | One cart-changing intent identified by a command request identifier and its complete declared input. It may be delivered one or more times. |
| Delivery Attempt | One submission of a logical command. Multiple identical delivery attempts may return the same successful result while producing one accepted change. |
| Open Cart | A cart that can still be changed. |
| Closed Cart | A cart that has been confirmed or cancelled and cannot be changed further. |
| Confirmed Cart | A closed cart that the Cart Manager has submitted as ready for the next business step. |
| Cancelled Cart | A closed cart that the Cart Manager has abandoned. |
| Cart Observation | The state of an existing cart observed by an actor at a point in time. It includes a system-issued, tamper-evident observation marker that is bound to that cart. |
| Cart Observation Marker | An opaque value representing the cart revision in an observation. It lets the system determine whether a later cart-changing request is still based on that observed state without accepting a client-supplied revision. |
| Cart Revision | The ordered position reached by an accepted cart change. It appears in change history and is represented opaquely by a cart observation marker when used for concurrency. |
| Concurrent Change Conflict | A requested cart change is based on a cart observation that is no longer current and cannot be accepted without risking a lost update. |
| Change History | The ordered record of accepted cart changes. Each entry reports the cart revision, change type, acceptance time and business data. |

Product A and product B in examples are readable labels for distinct product
identifier UUIDs.

### How Cart Observations Work

An observation is the complete cart state returned to an actor by a successful
cart view or cart-changing command, together with its observation marker. It
means: "this is the state of this cart after this particular accepted change."
The marker is evidence issued by the system for that cart state; it is not a cart
identifier and the actor does not construct it.

When requesting another change, the actor sends the cart identifier and marker
from the observation on which the new decision is based. If the cart has not
changed since that observation, the request may proceed to the business rules.
If another command has changed the cart, the marker is authentic but stale and
the request is a concurrent-change conflict. The actor then views the cart again
and decides whether to submit a new command using the newly returned observation.

There is one retry-specific distinction. The command that successfully changes
the cart makes its input observation stale, but repeating that same accepted
command with the same command request identifier returns the stored original
success. A different command request identifier using that old observation is a
conflict. Idempotency recognizes a retry; it does not make a stale observation
current again.

An observation does not lock or reserve a cart, does not represent a session,
and does not expire merely because time passes. It remains current until an
accepted command changes the cart. There is no observation before the first
accepted product addition because no cart yet exists.

More than one authentic marker may represent the same cart revision, for example
after signing-key rotation. Marker byte equality is not part of observation
equality; currency depends only on the cart and revision represented by the
marker.

### Command and Result Equality

Two delivery attempts have the same command input when they have the same
command type and the same values for every declared command field after those
values have been parsed. Representation details such as JSON whitespace, object
field order and uppercase versus lowercase UUID text do not make otherwise equal
inputs different. An undeclared field remains invalid input rather than becoming
part of command equality.

The exact original successful result means the same business response data,
including the original cart identifier, cart state and observation marker. It
does not include delivery-specific transport metadata such as a correlation
identifier, response date or tracing data.

### System-Wide Requirements

| ID | Requirement |
|---|---|
| SWR-001 | Cart identifiers, product identifiers and command request identifiers must be UUIDs. Command request identifiers are interpreted in one global namespace. A cart identifier returned by the system must be unique. A supplied cart identifier is valid only when it identifies an existing cart. A requested quantity must be a positive whole number no greater than 1000. The held quantity for one product must not exceed 1000. There is no business limit on the number of distinct products or accepted changes in one cart. |
| SWR-002 | A closed cart must reject every further cart-changing command, including adding product items, removing product items, confirming and cancelling. |
| SWR-003 | A confirmed cart and a cancelled cart are both closed carts. |
| SWR-004 | A request that fails a business rule must not partially change the cart. |
| SWR-005 | After a cart has been created by its first accepted product addition, every cart-changing request must be based on a cart observation. If that observation is no longer current, the system must reject the request as a concurrent-change conflict and must not partially change the cart. The first product addition has no prior cart identifier or observation. |
| SWR-006 | A conflict response must tell the actor that the cart has changed and that the actor must view the current cart before trying again. |
| SWR-007 | The cart stores product identifiers and quantities only. It must not accept, store, return or make decisions from product prices. A product identifier that is a UUID is treated as an opaque reference and is not checked against a product catalogue in this requirements version. |
| SWR-008 | Outcomes must let Cart Managers distinguish success, invalid input, business rejection and concurrent-change conflict. Invalid input means the request is malformed or outside a declared range, independently of cart state: a non-UUID or missing identifier, a requested quantity outside 1 through 1000, an undeclared field, an unauthentic observation marker, a cart identifier that identifies no cart, or a missing observation where one is required. Business rejection means the request is well formed but the cart's state forbids it: the cart is closed, a removal would take a held quantity below zero, a confirmation names a cart with no product items, or an addition would make the resulting held quantity for that product exceed 1000. |
| SWR-009 | User-facing translated labels or messages are outside the Shopping Cart System's requirements. The cart behavior returns stable outcomes; presentation channels may localize them. |
| SWR-010 | The system must be testable through observable behavior for every prepared use-case slice. |
| SWR-011 | Cart viewing reports confirmed and cancelled carts as closed and preserves their final product identifiers and quantities. The accepted change history is the place to distinguish how the cart was closed. |
| SWR-012 | This requirements version defines no quantified performance, availability or durability service levels beyond the correctness and no-partial-change requirements above. It defines no deletion or archival behavior: carts, accepted changes, cart views and accepted-command results are retained indefinitely. |
| SWR-013 | A request to change an existing cart that is not based on an observation of that cart must be rejected as invalid input. A first product addition made without a cart identifier creates a new cart and is the only cart-changing request that does not require a prior cart observation. |
| SWR-014 | If two otherwise acceptable logical commands with different command request identifiers are based on the same observation of an existing cart, the system must accept at most one of them. A command rejected because another command has already changed that observation must be reported as a concurrent-change conflict and must not partially change the cart. Multiple identical delivery attempts using one command request identifier are one logical command governed by `SWR-016`, not competing commands under this requirement. |
| SWR-015 | A valid first product addition made without a cart identifier or observation must atomically create one open cart containing that product quantity and return a unique system-generated cart identifier and current observation. An invalid or failed first addition must not create a cart or accepted change history. An add request that supplies only one of cart identifier or observation must be rejected as invalid input. |
| SWR-016 | Every logical cart-changing command must include a command request identifier from the system-wide namespace. Repeating an accepted command with the same request identifier and semantically equal complete input, whether later or concurrently, must return its exact original business result without accepting another change. This remains true if the cart has since changed, and accepted command results are retained indefinitely in this version. If otherwise acceptable delivery attempts concurrently use one previously unestablished request identifier with different command types or input, exactly one logical command must be accepted; after it is accepted, every non-equal attempt must be rejected as invalid input and must not change any cart. Only accepted commands establish replay behavior; an identifier used only by invalid, business-rejected or conflicting attempts remains available for later use. A missing or non-UUID request identifier must be rejected as invalid input. |
| SWR-017 | Change-history viewing must return the complete ordered accepted history without pagination. Revisions start at 1 and increase by one for each accepted change. Stable change types are `product-item-added`, `product-item-removed`, `cart-confirmed` and `cart-cancelled`. Addition and removal business data contains the product identifier and changed quantity, not the resulting total; confirmation and cancellation have no change-specific business data. Acceptance time is generated by the system when the change is durably accepted. History must not expose internal storage identifiers or persistence metadata. |
| SWR-018 | Every input object must reject fields that are not declared for that request. This includes, but is not limited to, price fields in a product item. |
| SWR-019 | A cart observation marker must be system-authenticated and bound to its cart. A marker that has been altered, fabricated or issued for a different cart must be rejected as invalid input rather than being treated as a current or stale observation. An observation has no time-based expiry: it remains current while the cart is unchanged and becomes stale when an accepted command changes the cart. |
| SWR-020 | Every successful cart-changing command must return the complete cart state produced by that command and its resulting observation. Every response that carries cart items, including a cart view, represents them as product-identifier and held-quantity pairs in ascending product-identifier UUID order, independently of the order in which the products were added. An idempotent replay returns the original successful result, which need not be the cart's current state if later commands have succeeded. |
| SWR-021 | Once a successful command result has been returned, subsequent cart-view and change-history requests must include that accepted change. |
| SWR-022 | Every cart-changing delivery attempt must be evaluated in this fixed order, and the first failing step alone determines the reported outcome: (1) input validity, giving invalid input; (2) accepted-command replay, giving the original successful result, or invalid input when the request identifier was established by non-equal input; (3) observation currency for commands concerning an existing cart, giving a concurrent-change conflict; (4) business rules, giving a business rejection. The observation step does not apply to a first product addition because no cart or observation yet exists. No step may partially change a cart. A stale observation of a cart that is also closed is therefore reported as a concurrent-change conflict, and an invalid request based on a stale observation is reported as invalid input. |

### Shared Concurrent-Change Extension

Concurrent-change handling is a shared extension of cart-changing use cases, not
a separate user-goal use case. It becomes visible when a Cart Manager submits a
change based on a cart observation that is no longer current.

| Shared Extension ID | Applies To | Behavior |
|---|---|---|
| EXT-CONFLICT-001 | Manage Cart Contents, Confirm Cart, Cancel Cart | If a requested change is based on an older cart observation, the system rejects it as a concurrent-change conflict and leaves the cart at the newer state. |
| EXT-CONFLICT-002 | Manage Cart Contents, Confirm Cart, Cancel Cart | If two actors submit distinct logical commands with different request identifiers based on the same cart observation, the system accepts no more than one command against that observation. Any other command based on the old observation is rejected as a conflict. |
| EXT-CONFLICT-003 | Manage Cart Contents, Confirm Cart, Cancel Cart | After a conflict, the actor can view the current cart and submit a new request based on the current state. |

### Shared Accepted-Command Retry Extension

Accepted-command retry handling applies to every cart-changing use case. It is
visible when a Cart Manager repeats a command because the original response was
lost or uncertain.

| Shared Extension ID | Applies To | Behavior |
|---|---|---|
| EXT-RETRY-001 | Manage Cart Contents, Confirm Cart, Cancel Cart | Repeating an accepted logical command with the same command request identifier and semantically equal complete input returns the exact original business result and accepts no additional change, even if the cart has subsequently changed. |
| EXT-RETRY-002 | Manage Cart Contents, Confirm Cart, Cancel Cart | Concurrent identical delivery attempts using one command request identifier return the same successful business result and accept one logical command and one change. |
| EXT-RETRY-003 | Manage Cart Contents, Confirm Cart, Cancel Cart | Reusing a command request identifier for a different command or non-equal input is invalid and changes no cart. If different otherwise acceptable attempts race while the identifier is unestablished, exactly one logical command is accepted and every non-equal loser is invalid. |

### Acceptance-Test Conventions

Unless a test case explicitly describes a repeat, reuse or race involving a
command request identifier, each cart-changing command uses a fresh globally
unused identifier. Unless a test explicitly describes an older, altered,
fabricated or different-cart observation, it uses the current authentic
observation for the named cart. "Same result" means the same business response
data as defined under Command and Result Equality, not identical transport
metadata. Every rejected command asserts one outcome category from `SWR-008`.

## 5. Use Cases

### UC-01 - Manage Cart Contents

**Primary Actors:** Customer, External Cart System

**Goal:** Add or remove product quantities, establishing a cart with the first
accepted addition.

**Trigger:** The Cart Manager requests an addition or removal of product quantity.

**Preconditions:**

- the Cart Manager has supplied product-item details for the change
- for the first addition, the Cart Manager has no cart identifier or cart
  observation
- for a change to an existing cart, the Cart Manager has supplied or attempted
  to supply its system-issued identifier and the observation on which the change
  is based
- the Cart Manager has supplied or attempted to supply a command request
  identifier

**Postconditions:**

- On rejection, the cart is not changed and the Cart Manager receives a clear
  outcome.
- On success, the cart reflects the accepted product quantity change.
- On success, the cart remains open unless another use case closes it.
- On the first successful addition, the system returns a unique cart identifier.
- Repeating any accepted content-change command returns its exact original
  successful result without accepting another change.
- On success, the Cart Manager can view the revised cart.

#### Basic Flow - Add First Product Item

1. Cart Manager, without an existing cart identifier, requests adding a quantity
   of a product item.
2. System validates the product item and command request identifier.
3. System finds that the command request identifier has not already been
   accepted, and so continues rather than replaying an earlier result.
4. System generates a unique cart identifier.
5. System atomically accepts the product addition and establishes an open cart
   containing that product quantity.
6. System returns the cart identifier and makes the cart available for viewing
   with its first cart observation.
7. Use case ends with the cart open.

#### Alternate Flows

| ID | Alternate Flow |
|---|---|
| UC-01-A1 | The product item is invalid. The system rejects the request as invalid input and leaves an existing cart unchanged, or creates no cart if this was a first addition. |
| UC-01-A2 | The cart is closed. The system rejects the request as a business rejection and leaves the cart unchanged. |
| UC-01-A3 | Within UC-01-A10, the product is already in the cart. The system adds the requested quantity to the held quantity for that product. |
| UC-01-A4 | Cart Manager requests removing a quantity of a product item. The system checks that the request is based on the current cart observation, checks the cart can be changed, checks enough quantity is held, accepts the removal, decreases the held quantity, and makes the updated cart available for viewing with a new cart observation. |
| UC-01-A5 | Removal would take the held quantity below zero. The system rejects the request as a business rejection and leaves the cart unchanged. |
| UC-01-A6 | Removal takes the held quantity to zero. The system removes that product from the visible cart contents but the cart remains open. |
| UC-01-A7 | The requested change is based on an older cart observation. The system rejects the request as a concurrent-change conflict and leaves the cart at the newer state. |
| UC-01-A8 | A request that claims to change an existing cart supplies a cart identifier that does not identify an existing cart. The system rejects the request as invalid input and no cart is changed. |
| UC-01-A9 | A request to change an existing cart is not based on a cart observation. The system rejects the request as invalid input and leaves the cart unchanged. |
| UC-01-A10 | Cart Manager requests adding a product item to an existing cart, whether or not that product is already held. The system checks that the request is based on the current observation, checks that the cart is open, accepts the addition, and returns the updated cart with a new observation. |
| UC-01-A11 | Cart Manager repeats an accepted content-change command with the same command request identifier and complete input. The system returns the exact original successful result without accepting another change, even if the cart has subsequently changed. |
| UC-01-A12 | Cart Manager reuses a command request identifier for a different command or different input. The system rejects the request as invalid input and does not change a cart. |
| UC-01-A13 | A command request identifier is missing or is not a UUID. The system rejects the request as invalid input and changes no cart. |

#### Slices

| Slice ID | Name | Flow and Requirement Coverage | Value |
|---|---|---|---|
| UC-01/S01 | Add first product item safely | Basic flow plus UC-01-A11, UC-01-A12, UC-01-A13, EXT-RETRY-001 through EXT-RETRY-003 and SWR-016 | A Cart Manager can begin a cart and safely retry when the response is uncertain. |
| UC-01/S02 | Add product quantity to an existing cart | UC-01-A3, UC-01-A10, EXT-RETRY-001, EXT-RETRY-002 and SWR-020 | A Cart Manager can add products or quantity to an identified cart and safely retry an accepted addition. |
| UC-01/S03 | Remove product quantity | UC-01-A4, UC-01-A6 and EXT-RETRY-001 | A Cart Manager can correct cart quantities and safely retry an accepted removal. |
| UC-01/S04 | Reject invalid or disallowed content changes | UC-01-A1, UC-01-A2, UC-01-A5, UC-01-A8, UC-01-A9, UC-01-A12, UC-01-A13 plus SWR-018 and SWR-019 | Invalid or disallowed changes do not corrupt the cart. |
| UC-01/S05 | Reject conflicting content changes | UC-01-A7 plus EXT-CONFLICT-001, EXT-CONFLICT-002, EXT-CONFLICT-003 and SWR-022 | Content changes based on stale cart views are not silently accepted. |

#### Use-Case Test Cases

| Test Case ID | Slice | Given | When | Then |
|---|---|---|---|---|
| UC-01/S01/TC01 | UC-01/S01 | Cart Manager does not yet have a cart identifier | Cart Manager adds 2 units of product A using a new command request identifier | system returns a unique cart-identifier UUID and an open cart showing product A quantity 2 with its first observation |
| UC-01/S01/TC02 | UC-01/S01 | Cart Manager has no existing cart and makes two first-addition requests with different command request identifiers | both first additions succeed | system returns different cart identifiers and the two carts can be managed independently |
| UC-01/S01/TC03 | UC-01/S01 | a first addition succeeded and the cart was subsequently changed using another command request identifier | Cart Manager repeats the first addition using its original request identifier and complete input | system returns the exact original successful result, including its original observation; no cart or history is changed and the cart's current contents remain at the later state |
| UC-01/S01/TC04 | UC-01/S01 | a first-addition command request identifier has already succeeded | Cart Manager reuses it with a different product identifier or quantity | request is rejected as invalid input and the original cart remains unchanged |
| UC-01/S01/TC05 | UC-01/S01 | Cart Manager has no cart and the command request identifier is missing or is not a UUID | Cart Manager submits the first addition | request is rejected as invalid input and no cart or accepted change history is created |
| UC-01/S01/TC06 | UC-01/S01 | two first-addition commands use the same command request identifier, product identifier and quantity | both requests are submitted concurrently | both receive the same successful result; exactly one cart and one accepted addition exist and quantity is applied once |
| UC-01/S01/TC07 | UC-01/S01 | a first addition using a given command request identifier was rejected as invalid input | Cart Manager submits a valid first addition reusing that same request identifier | addition is accepted and a cart is created, because an identifier used only by a rejected attempt remains available |
| UC-01/S01/TC08 | UC-01/S01 | two otherwise valid first-addition delivery attempts use the same previously unestablished command request identifier but different product identifiers | both attempts are submitted concurrently | exactly one logical command is accepted and creates one cart; the other receives invalid input and no second cart or accepted addition is created |
| UC-01/S02/TC01 | UC-01/S02 | Cart Manager has observed cart contains product A quantity 2 | Cart Manager adds 3 more units of product A based on that observation | cart is open and shows product A quantity 5 |
| UC-01/S02/TC02 | UC-01/S02 | cart contains product B only, and product A's UUID sorts before product B's UUID | Cart Manager adds product A based on the current observation | cart shows product A before product B, confirming items are ordered by product-identifier UUID and not by the order they were added |
| UC-01/S02/TC03 | UC-01/S02 | an addition to an existing cart succeeded and the cart subsequently changed | Cart Manager repeats the addition using its original command request identifier and complete input | system returns the exact original successful result and accepts no additional change; the cart remains at its later state |
| UC-01/S02/TC04 | UC-01/S02 | two identical additions to an existing cart use the same new command request identifier and observation | both requests are submitted concurrently | both receive the same successful result and exactly one addition is accepted |
| UC-01/S02/TC05 | UC-01/S02 | Cart Manager received an observation of an open cart and time has passed without an accepted command changing that cart | Cart Manager adds a product using that observation and a fresh command request identifier | addition is accepted because elapsed time alone does not expire the observation |
| UC-01/S03/TC01 | UC-01/S03 | Cart Manager has observed cart contains product A quantity 5 | Cart Manager removes 2 units of product A based on that observation | cart is open and shows product A quantity 3 |
| UC-01/S03/TC02 | UC-01/S03 | Cart Manager has observed cart contains product A quantity 2 | Cart Manager removes 2 units of product A based on that observation | cart remains open and product A no longer appears in contents |
| UC-01/S03/TC03 | UC-01/S03 | a removal succeeded using a new command request identifier | Cart Manager repeats the same removal using that request identifier and complete input | system returns the exact original successful result, no additional quantity is removed and no additional history entry is accepted |
| UC-01/S04/TC01 | UC-01/S04 | Cart Manager has observed the cart is closed | Cart Manager adds a product item based on that observation | request receives a business rejection and cart remains unchanged |
| UC-01/S04/TC02 | UC-01/S04 | Cart Manager has observed cart contains product A quantity 1 | Cart Manager removes 2 units of product A based on that observation | request receives a business rejection and cart still shows product A quantity 1 |
| UC-01/S04/TC03 | UC-01/S04 | product identifier is missing or is not a UUID | Cart Manager submits the change | request is rejected as invalid input; an existing cart remains unchanged and a first addition creates no cart |
| UC-01/S04/TC04 | UC-01/S04 | product quantity is zero, negative, greater than 1000 or not a whole number | Cart Manager submits the change | request is rejected as invalid input; an existing cart remains unchanged and a first addition creates no cart |
| UC-01/S04/TC05 | UC-01/S04 | product item input contains a unit price or another price field | Cart Manager submits the change | request is rejected as invalid input; no price is stored and an existing cart remains unchanged or a first addition creates no cart |
| UC-01/S04/TC06 | UC-01/S04 | a request claims to change an existing cart but its cart identifier does not identify one | Cart Manager submits a content change | request is rejected as invalid input and no cart is changed |
| UC-01/S04/TC07 | UC-01/S04 | Cart Manager has an existing cart but has not supplied its observation | Cart Manager submits a content change | request is rejected as invalid input and cart remains unchanged |
| UC-01/S04/TC08 | UC-01/S04 | Cart Manager supplies a cart observation without its cart identifier | Cart Manager submits an addition | request is rejected as invalid input and no existing or new cart is changed |
| UC-01/S04/TC09 | UC-01/S04 | cart contains product A quantity 999 | Cart Manager requests adding 2 units of product A | request receives a business rejection and product A remains at quantity 999 |
| UC-01/S04/TC10 | UC-01/S04 | an existing-cart content command has no command request identifier or a non-UUID identifier | Cart Manager submits the command | request is rejected as invalid input and the cart remains unchanged |
| UC-01/S04/TC11 | UC-01/S04 | a command request identifier has already succeeded | Cart Manager reuses it for a different command, cart or input | request is rejected as invalid input and no cart is changed |
| UC-01/S04/TC12 | UC-01/S04 | a content-change input object contains a field not declared for that request | Cart Manager submits the command | request is rejected as invalid input and no cart is changed |
| UC-01/S04/TC13 | UC-01/S04 | Cart Manager supplies an altered, fabricated or different-cart observation marker | Cart Manager submits a content change | request is rejected as invalid input rather than as a conflict and no cart is changed |
| UC-01/S04/TC14 | UC-01/S04 | Cart Manager has observed a closed cart containing product A quantity 2 | Cart Manager removes 1 unit of product A using that current observation and a fresh command request identifier | request receives a business rejection and the closed cart remains unchanged |
| UC-01/S05/TC01 | UC-01/S05 | Cart Manager observed an older cart observation | Cart Manager submits a content change based on that older observation | request is rejected as a conflict and cart remains at the newer state |
| UC-01/S05/TC02 | UC-01/S05 | two Cart Managers observed the same cart observation | both submit content changes based on that observation | no more than one change based on that observation is accepted; rejected changes leave the cart unchanged and are reported as conflicts |
| UC-01/S05/TC03 | UC-01/S05 | two Cart Managers observed the same cart immediately after its first product addition | both submit further content changes based on that same observation | no more than one further change is accepted; rejected changes leave the cart unchanged and are reported as conflicts |
| UC-01/S05/TC04 | UC-01/S05 | a content change was rejected as a conflict | Cart Manager views the cart again and resubmits the change based on the newly returned observation, using a new command request identifier | resubmitted change is accepted and the cart reflects it |
| UC-01/S05/TC05 | UC-01/S05 | Cart Manager holds an observation of an open cart that has since been confirmed by another actor | Cart Manager adds a product item based on that older observation | request is reported as a concurrent-change conflict rather than as a closed-cart business rejection, and the cart remains closed |
| UC-01/S05/TC06 | UC-01/S05 | Cart Manager holds an older observation and supplies a requested quantity of zero | Cart Manager submits the content change | request is rejected as invalid input rather than as a concurrent-change conflict, and the cart remains unchanged |
| UC-01/S05/TC07 | UC-01/S05 | Cart Manager observed product A quantity 2, after which another command reduced it to quantity 1 | Cart Manager requests removing 2 units using the older observation and a fresh command request identifier | request is reported as a concurrent-change conflict rather than as an insufficient-quantity business rejection, and product A remains at quantity 1 |

### UC-02 - View Cart

**Primary Actors:** Customer, External Cart System

**Goal:** See the current status and contents of a cart.

**Trigger:** The Cart Manager requests the current view of a cart.

**Preconditions:**

- the Cart Manager has supplied or attempted to supply a system-issued cart identifier

**Postconditions:**

- Viewing a cart does not change it.
- On success, the Cart Manager can see whether the cart is open or closed.
- On success, the Cart Manager can see product quantities in the cart.
- On success, the current view includes a cart observation for later
  cart-changing requests.

#### Basic Flow

1. Cart Manager requests the current view of a cart.
2. System finds the cart's current state.
3. System reports cart status, product quantities and the current cart observation.
4. Use case ends without changing the cart.

#### Alternate Flows

| ID | Alternate Flow |
|---|---|
| UC-02-A1 | The cart has just been established by its first accepted product addition. The system reports the open cart, its first product quantity and its current observation. |
| UC-02-A2 | The cart is closed. The system reports the closed status without exposing whether confirmation or cancellation closed it. |
| UC-02-A3 | The cart is closed. The system preserves and returns the final product identifiers and quantities held when it was closed. |
| UC-02-A4 | The supplied cart identifier does not identify an existing cart. The system rejects the view request as invalid input and does not report misleading cart contents or an observation. |

#### Slices

| Slice ID | Name | Flow and Requirement Coverage | Value |
|---|---|---|---|
| UC-02/S01 | View a newly established cart | UC-02-A1 | A Cart Manager can use the identifier returned by the first addition to inspect the cart. |
| UC-02/S02 | View open cart | Basic flow plus SWR-021 | A Cart Manager can review current contents before changing or confirming. |
| UC-02/S03 | View closed cart | UC-02-A2 | A Cart Manager can see that a cart is no longer changeable. |
| UC-02/S04 | Retain closed-cart contents | UC-02-A3 plus SWR-020 | A Cart Manager can still review the final selected quantities after closure. |
| UC-02/S05 | Reject invalid view identifier | UC-02-A4 plus SWR-018 | A Cart Manager does not receive misleading results for an invalid identifier. |

#### Use-Case Test Cases

| Test Case ID | Slice | Given | When | Then |
|---|---|---|---|---|
| UC-02/S01/TC01 | UC-02/S01 | a first addition established a cart containing product A quantity 1 | Cart Manager views the cart using the returned cart identifier | system reports open status, product A quantity 1 and the current cart observation |
| UC-02/S02/TC01 | UC-02/S02 | cart contains product A quantity 2 | Cart Manager views the cart | system reports open status, product A quantity 2 and a current cart observation |
| UC-02/S02/TC02 | UC-02/S02 | a content-change command has just returned success | Cart Manager immediately views the cart | view includes the change accepted by that command |
| UC-02/S02/TC03 | UC-02/S02 | cart contains product A quantity 2 | Cart Manager views the cart twice with no intervening command | both views report the same cart state and current revision, and no accepted change is added to history; exact observation-marker equality is not required |
| UC-02/S03/TC01 | UC-02/S03 | cart has been confirmed | Cart Manager views the cart | system reports closed status without reporting the closure reason and includes a current cart observation |
| UC-02/S03/TC02 | UC-02/S03 | cart has been cancelled | Cart Manager views the cart | system reports closed status without reporting the closure reason and includes a current cart observation |
| UC-02/S04/TC01 | UC-02/S04 | cart held product A quantity 2 and product B quantity 1 when it was closed, product B having been added first, and product A's UUID sorting before product B's | Cart Manager views the closed cart | viewed cart still shows product A quantity 2 and product B quantity 1, with product A listed before product B |
| UC-02/S05/TC01 | UC-02/S05 | supplied cart identifier does not identify an existing cart | Cart Manager views the cart | request is rejected as invalid input and no cart contents or observation are reported |
| UC-02/S05/TC02 | UC-02/S05 | a view-cart input object contains a field not declared for that request | Cart Manager submits the query | request is rejected as invalid input and no cart contents or observation are reported |

### UC-03 - Confirm Cart

**Primary Actors:** Customer, External Cart System

**Goal:** Mark an open cart as ready for the next business step.

**Trigger:** The Cart Manager requests confirmation of the cart.

**Preconditions:**

- the Cart Manager has supplied or attempted to supply a system-issued cart identifier
- the Cart Manager has supplied or attempted to supply the cart observation on
  which the confirmation is based
- the Cart Manager has supplied or attempted to supply a command request identifier

**Postconditions:**

- On rejection, the cart is not changed and the Cart Manager receives a clear
  outcome.
- On conflict, the cart remains at the newer state.
- On success, an eligible cart becomes closed as confirmed.
- On success, later attempts to change cart contents are rejected.
- Repeating an accepted confirmation returns its exact original successful result
  without accepting another confirmation.

#### Basic Flow

1. Cart Manager requests confirmation of a cart using a command request identifier.
2. System validates the request, including the command request identifier.
3. System finds that the command request identifier has not already been
   accepted, and so continues rather than replaying an earlier result.
4. System checks that the request is based on the current cart observation.
5. System checks that the cart is open.
6. System checks that the cart contains at least one product item.
7. System accepts the confirmation.
8. System marks the cart as confirmed and closed.
9. Use case ends with the cart no longer accepting content changes.

#### Alternate Flows

| ID | Alternate Flow |
|---|---|
| UC-03-A1 | The cart is open but contains zero product items. The system rejects confirmation as a business rejection and leaves the cart open. |
| UC-03-A2 | The cart is already closed. The system rejects confirmation as a business rejection and leaves the cart unchanged. |
| UC-03-A3 | The confirmation is based on an older cart observation. The system rejects it as a concurrent-change conflict and leaves the cart at the newer state. |
| UC-03-A4 | The supplied cart identifier does not identify an existing cart. The system rejects confirmation as invalid input and no cart is changed. |
| UC-03-A5 | The request is not based on a cart observation. The system rejects confirmation as invalid input and leaves the cart unchanged. |
| UC-03-A6 | Cart Manager repeats an accepted confirmation with the same command request identifier and complete input. The system returns the exact original successful result without accepting another confirmation. |
| UC-03-A7 | The command request identifier is missing, is not a UUID, or was established by an accepted command with different input. The system rejects confirmation as invalid input and no cart is changed. |

#### Slices

| Slice ID | Name | Flow and Requirement Coverage | Value |
|---|---|---|---|
| UC-03/S01 | Confirm an eligible cart | Basic flow plus UC-03-A6, EXT-RETRY-001 through EXT-RETRY-003 and SWR-016 | A Cart Manager can complete cart selection and safely retry an accepted confirmation. |
| UC-03/S02 | Reject confirmation without items | UC-03-A1 | Empty carts are not confirmed accidentally. |
| UC-03/S03 | Reject confirmation after closure | UC-03-A2 | Closed cart state remains stable. |
| UC-03/S04 | Reject conflicting confirmation | UC-03-A3 plus EXT-CONFLICT-001, EXT-CONFLICT-002, EXT-CONFLICT-003 | Confirmation cannot silently override newer changes. |
| UC-03/S05 | Reject invalid confirmation request | UC-03-A4, UC-03-A5, UC-03-A7 plus SWR-018 and SWR-019 | Invalid confirmation requests do not create or close carts. |

#### Use-Case Test Cases

| Test Case ID | Slice | Given | When | Then |
|---|---|---|---|---|
| UC-03/S01/TC01 | UC-03/S01 | Cart Manager has observed an open cart containing product A quantity 1 | Cart Manager confirms the cart based on that observation | cart becomes confirmed and closed |
| UC-03/S01/TC02 | UC-03/S01 | Cart Manager has observed the cart has been confirmed | Cart Manager attempts to add a product based on that current observation using a fresh command request identifier | content change receives a closed-cart business rejection |
| UC-03/S01/TC03 | UC-03/S01 | confirmation succeeded using a new command request identifier | Cart Manager repeats the same confirmation using that request identifier and complete input | system returns the exact original successful result and one confirmation appears in history |
| UC-03/S01/TC04 | UC-03/S01 | two identical confirmations of an eligible cart use the same new command request identifier and observation | both requests are submitted concurrently | both receive the same successful result and exactly one confirmation is accepted |
| UC-03/S01/TC05 | UC-03/S01 | a confirmation command request identifier has already succeeded | Cart Manager reuses it for a different cart or a different command | request is rejected as invalid input and no cart is changed |
| UC-03/S02/TC01 | UC-03/S02 | Cart Manager has observed an open cart after its only product quantity was fully removed | Cart Manager confirms the cart based on that observation | confirmation receives a business rejection and cart remains open and empty |
| UC-03/S02/TC02 | UC-03/S02 | Cart Manager has observed an open cart after all quantities of multiple products were removed | Cart Manager confirms the cart based on that observation | confirmation receives a business rejection and cart remains open and empty |
| UC-03/S03/TC01 | UC-03/S03 | Cart Manager has observed the cart has been confirmed | Cart Manager confirms the cart again based on that observation, using a new command request identifier | confirmation receives a business rejection and cart remains closed |
| UC-03/S03/TC02 | UC-03/S03 | Cart Manager has observed the cart has been cancelled | Cart Manager confirms the cart based on that observation, using a new command request identifier | confirmation receives a business rejection and cart remains closed |
| UC-03/S04/TC01 | UC-03/S04 | Cart Manager observed an older cart observation | Cart Manager confirms based on that older observation | confirmation is rejected as a conflict and cart remains at the newer state |
| UC-03/S04/TC02 | UC-03/S04 | two Cart Managers observed the same cart observation | both submit confirmations based on that observation using different command request identifiers | no more than one confirmation is accepted; the rejected one leaves the cart unchanged and is reported as a conflict |
| UC-03/S04/TC03 | UC-03/S04 | a confirmation was rejected as a conflict | Cart Manager views the cart again and reconfirms based on the newly returned observation, using a new command request identifier | reconfirmation is accepted and the cart becomes confirmed and closed |
| UC-03/S04/TC04 | UC-03/S04 | Cart Manager holds an observation of an open cart that has since been cancelled by another actor | Cart Manager confirms using the older observation and a fresh command request identifier | confirmation is reported as a concurrent-change conflict rather than as a closed-cart business rejection, and the cart remains cancelled |
| UC-03/S05/TC01 | UC-03/S05 | supplied cart identifier does not identify an existing cart | Cart Manager confirms the cart | confirmation is rejected as invalid input and no cart is changed |
| UC-03/S05/TC02 | UC-03/S05 | Cart Manager has not supplied a cart observation for the confirmation | Cart Manager confirms the cart | confirmation is rejected as invalid input and cart remains unchanged |
| UC-03/S05/TC03 | UC-03/S05 | the confirmation has no command request identifier or a non-UUID identifier | Cart Manager confirms the cart | confirmation is rejected as invalid input and cart remains unchanged |
| UC-03/S05/TC04 | UC-03/S05 | a confirmation input object contains a field not declared for that request | Cart Manager submits the confirmation | confirmation is rejected as invalid input and no cart is changed |
| UC-03/S05/TC05 | UC-03/S05 | Cart Manager supplies an altered, fabricated or different-cart observation marker | Cart Manager confirms the cart | confirmation is rejected as invalid input rather than as a conflict and no cart is changed |

### UC-04 - Cancel Cart

**Primary Actors:** Customer, External Cart System

**Goal:** Abandon a cart so that it cannot be changed further.

**Trigger:** The Cart Manager requests cancellation of the cart.

**Preconditions:**

- the Cart Manager has supplied or attempted to supply a system-issued cart identifier
- the Cart Manager has supplied or attempted to supply the cart observation on
  which the cancellation is based
- the Cart Manager has supplied or attempted to supply a command request identifier

**Postconditions:**

- On rejection, the cart is not changed and the Cart Manager receives a clear
  outcome.
- On conflict, the cart remains at the newer state.
- On success, the cart becomes closed as cancelled.
- On success, later attempts to change cart contents are rejected.
- Repeating an accepted cancellation returns its exact original successful result
  without accepting another cancellation.

#### Basic Flow

1. Cart Manager requests cancellation of a cart using a command request identifier.
2. System validates the request, including the command request identifier.
3. System finds that the command request identifier has not already been
   accepted, and so continues rather than replaying an earlier result.
4. System checks that the request is based on the current cart observation.
5. System checks that the cart is open.
6. System accepts the cancellation.
7. System marks the cart as cancelled and closed.
8. Use case ends with the cart no longer accepting content changes.

#### Alternate Flows

| ID | Alternate Flow |
|---|---|
| UC-04-A1 | The cart is open but all product quantities have been removed. The system accepts cancellation and closes the empty cart. |
| UC-04-A2 | The cart is already closed. The system rejects cancellation as a business rejection and leaves the cart unchanged. |
| UC-04-A3 | The cancellation is based on an older cart observation. The system rejects it as a concurrent-change conflict and leaves the cart at the newer state. |
| UC-04-A4 | The supplied cart identifier does not identify an existing cart. The system rejects cancellation as invalid input and no cart is changed. |
| UC-04-A5 | The request is not based on a cart observation. The system rejects cancellation as invalid input and leaves the cart unchanged. |
| UC-04-A6 | Cart Manager repeats an accepted cancellation with the same command request identifier and complete input. The system returns the exact original successful result without accepting another cancellation. |
| UC-04-A7 | The command request identifier is missing, is not a UUID, or was established by an accepted command with different input. The system rejects cancellation as invalid input and no cart is changed. |

#### Slices

| Slice ID | Name | Flow and Requirement Coverage | Value |
|---|---|---|---|
| UC-04/S01 | Cancel an empty open cart | UC-04-A1 | A Cart Manager can abandon a cart after removing all product quantities. |
| UC-04/S02 | Cancel an open cart | Basic flow plus UC-04-A6, EXT-RETRY-001 through EXT-RETRY-003 and SWR-016 | A Cart Manager can abandon selected contents and safely retry an accepted cancellation. |
| UC-04/S03 | Reject cancellation after closure | UC-04-A2 | Closed cart state remains stable. |
| UC-04/S04 | Reject conflicting cancellation | UC-04-A3 plus EXT-CONFLICT-001, EXT-CONFLICT-002, EXT-CONFLICT-003 | Cancellation cannot silently override newer cart changes. |
| UC-04/S05 | Reject invalid cancellation request | UC-04-A4, UC-04-A5, UC-04-A7 plus SWR-018 and SWR-019 | Invalid cancellation requests do not create or close carts. |

#### Use-Case Test Cases

| Test Case ID | Slice | Given | When | Then |
|---|---|---|---|---|
| UC-04/S01/TC01 | UC-04/S01 | Cart Manager has observed an open cart after all product quantities were removed | Cart Manager cancels the cart based on that observation | cart becomes cancelled and closed |
| UC-04/S02/TC01 | UC-04/S02 | Cart Manager has observed an open cart containing product A | Cart Manager cancels the cart based on that observation | cart becomes cancelled and closed |
| UC-04/S02/TC02 | UC-04/S02 | Cart Manager has observed the cart has been cancelled | Cart Manager attempts to add a product based on that current observation using a fresh command request identifier | content change receives a closed-cart business rejection |
| UC-04/S02/TC03 | UC-04/S02 | cancellation succeeded using a new command request identifier | Cart Manager repeats the same cancellation using that request identifier and complete input | system returns the exact original successful result and one cancellation appears in history |
| UC-04/S02/TC04 | UC-04/S02 | two identical cancellations of an open cart use the same new command request identifier and observation | both requests are submitted concurrently | both receive the same successful result and exactly one cancellation is accepted |
| UC-04/S02/TC05 | UC-04/S02 | a cancellation command request identifier has already succeeded | Cart Manager reuses it for a different cart or a different command | request is rejected as invalid input and no cart is changed |
| UC-04/S03/TC01 | UC-04/S03 | Cart Manager has observed the cart has been cancelled | Cart Manager cancels the cart again based on that observation, using a new command request identifier | cancellation receives a business rejection and cart remains closed |
| UC-04/S03/TC02 | UC-04/S03 | Cart Manager has observed the cart has been confirmed | Cart Manager cancels the cart based on that observation, using a new command request identifier | cancellation receives a business rejection and cart remains closed |
| UC-04/S04/TC01 | UC-04/S04 | Cart Manager observed an older cart observation | Cart Manager cancels based on that older observation | cancellation is rejected as a conflict and cart remains at the newer state |
| UC-04/S04/TC02 | UC-04/S04 | two Cart Managers observed the same cart observation | one submits cancellation and the other submits a content change based on that observation | no more than one change based on that observation is accepted; rejected changes leave the cart unchanged and are reported as conflicts |
| UC-04/S04/TC03 | UC-04/S04 | a cancellation was rejected as a conflict | Cart Manager views the cart again and recancels based on the newly returned observation, using a new command request identifier | recancellation is accepted and the cart becomes cancelled and closed |
| UC-04/S04/TC04 | UC-04/S04 | Cart Manager holds an observation of an open cart that has since been confirmed by another actor | Cart Manager cancels using the older observation and a fresh command request identifier | cancellation is reported as a concurrent-change conflict rather than as a closed-cart business rejection, and the cart remains confirmed |
| UC-04/S05/TC01 | UC-04/S05 | supplied cart identifier does not identify an existing cart | Cart Manager cancels the cart | cancellation is rejected as invalid input and no cart is changed |
| UC-04/S05/TC02 | UC-04/S05 | Cart Manager has not supplied a cart observation for the cancellation | Cart Manager cancels the cart | cancellation is rejected as invalid input and cart remains unchanged |
| UC-04/S05/TC03 | UC-04/S05 | the cancellation has no command request identifier or a non-UUID identifier | Cart Manager cancels the cart | cancellation is rejected as invalid input and cart remains unchanged |
| UC-04/S05/TC04 | UC-04/S05 | a cancellation input object contains a field not declared for that request | Cart Manager submits the cancellation | cancellation is rejected as invalid input and no cart is changed |
| UC-04/S05/TC05 | UC-04/S05 | Cart Manager supplies an altered, fabricated or different-cart observation marker | Cart Manager cancels the cart | cancellation is rejected as invalid input rather than as a conflict and no cart is changed |

### UC-05 - Review Cart Change History

**Primary Actors:** Support User

**Goal:** Understand which changes have been accepted for a cart.

**Trigger:** A support or audit need arises for a specific cart.

**Preconditions:**

- the Support User has supplied or attempted to supply a system-issued cart identifier
- the request is treated as permitted; authentication and authorization are
  outside this requirements version

**Postconditions:**

- Requesting history does not change the cart.
- On success, the Support User can see accepted changes in the order they were
  accepted.
- Each accepted change reports its cart revision, stable change type, acceptance
  time and business data without exposing storage internals.
- On success, rejected or conflicted attempts are not presented as accepted cart
  changes.

#### Basic Flow

1. Support User requests the change history of a cart.
2. System finds the accepted changes for that cart.
3. System presents the accepted changes in order.
4. Use case ends without changing the cart.

#### Alternate Flows

| ID | Alternate Flow |
|---|---|
| UC-05-A1 | The cart has only its first accepted product addition. The system presents that addition as the first accepted change. |
| UC-05-A2 | Support User reviews a history entry. The system reports its sequential cart revision, defined stable change type, system-generated acceptance time and defined business data without exposing internal storage identifiers or persistence metadata. |
| UC-05-A3 | The supplied cart identifier does not identify an existing cart. The system rejects the history request as invalid input and does not report misleading history. |

#### Slices

| Slice ID | Name | Flow and Requirement Coverage | Value |
|---|---|---|---|
| UC-05/S01 | Review existing history | Basic flow plus SWR-021 | A Support User can explain the accepted state of a cart. |
| UC-05/S02 | Review initial history | UC-05-A1 | A Support User can see the product addition that established a cart. |
| UC-05/S03 | Review explanatory history details | UC-05-A2 plus SWR-017 | History contains enough stable information to explain each accepted change without leaking storage internals. |
| UC-05/S04 | Reject invalid history identifier | UC-05-A3 plus SWR-018 | A Support User does not receive misleading history for an invalid identifier. |

#### Use-Case Test Cases

| Test Case ID | Slice | Given | When | Then |
|---|---|---|---|---|
| UC-05/S01/TC01 | UC-05/S01 | a cart has accepted add, remove and confirm changes in that order | Support User reviews history | entries are revisions 1, 2 and 3 with change types `product-item-added`, `product-item-removed` and `cart-confirmed` in that order |
| UC-05/S01/TC02 | UC-05/S01 | a conflicting change was rejected | Support User reviews accepted history | rejected conflicting change is not shown as accepted |
| UC-05/S01/TC03 | UC-05/S01 | a cart has accepted a cancellation | Support User reviews history | accepted cancellation appears at its sequential revision with change type `cart-cancelled` and no change-specific business data |
| UC-05/S01/TC04 | UC-05/S01 | a cart-changing command has just returned success | Support User immediately reviews history | history includes the change accepted by that command |
| UC-05/S01/TC05 | UC-05/S01 | a cart has accepted changes | Support User reviews history twice with no intervening command | both reviews report the same entries, and the cart's contents, status and current observation are unchanged |
| UC-05/S02/TC01 | UC-05/S02 | a first product addition established the cart and no later changes were accepted | Support User reviews history | the first product addition is shown as the first and only accepted change |
| UC-05/S03/TC01 | UC-05/S03 | a cart held product A quantity 2 and then accepted an addition of 3 units | Support User reviews history | the later addition entry reports its sequential cart revision, change type `product-item-added`, system-generated acceptance time, product identifier and changed quantity 3 rather than resulting total 5, and does not expose an internal storage identifier or persistence metadata |
| UC-05/S03/TC02 | UC-05/S03 | a cart held product A quantity 5 and then accepted removal of 2 units | Support User reviews history | the removal entry reports change type `product-item-removed`, product identifier and changed quantity 2 rather than resulting total 3 |
| UC-05/S04/TC01 | UC-05/S04 | supplied cart identifier does not identify an existing cart | Support User reviews history | request is rejected as invalid input and no history is reported |
| UC-05/S04/TC02 | UC-05/S04 | a change-history input object contains a field not declared for that request | Support User submits the query | request is rejected as invalid input and no history is reported |

## 6. Slice Backlog Summary

| Slice ID | Slice Name | Priority | State |
|---|---|---|---|
| UC-01/S01 | Add first product item safely | Must | Prepared |
| UC-01/S02 | Add product quantity to an existing cart | Must | Prepared |
| UC-01/S03 | Remove product quantity | Must | Prepared |
| UC-01/S04 | Reject invalid or disallowed content changes | Must | Prepared |
| UC-01/S05 | Reject conflicting content changes | Must | Prepared |
| UC-02/S01 | View a newly established cart | Must | Prepared |
| UC-02/S02 | View open cart | Must | Prepared |
| UC-02/S03 | View closed cart | Must | Prepared |
| UC-02/S04 | Retain closed-cart contents | Must | Prepared |
| UC-02/S05 | Reject invalid view identifier | Must | Prepared |
| UC-03/S01 | Confirm an eligible cart | Must | Prepared |
| UC-03/S02 | Reject confirmation without items | Must | Prepared |
| UC-03/S03 | Reject confirmation after closure | Must | Prepared |
| UC-03/S04 | Reject conflicting confirmation | Must | Prepared |
| UC-03/S05 | Reject invalid confirmation request | Must | Prepared |
| UC-04/S01 | Cancel an empty open cart | Must | Prepared |
| UC-04/S02 | Cancel an open cart | Must | Prepared |
| UC-04/S03 | Reject cancellation after closure | Must | Prepared |
| UC-04/S04 | Reject conflicting cancellation | Must | Prepared |
| UC-04/S05 | Reject invalid cancellation request | Must | Prepared |
| UC-05/S01 | Review existing history | Must | Prepared |
| UC-05/S02 | Review initial history | Must | Prepared |
| UC-05/S03 | Review explanatory history details | Must | Prepared |
| UC-05/S04 | Reject invalid history identifier | Must | Prepared |

### System-Wide Requirement Traceability

The following table identifies the slices to which each system-wide requirement
applies and representative verification evidence. The test references are not an
exhaustive list when a requirement governs many rejection paths.

| Requirement | Applicable Slices | Representative Evidence |
|---|---|---|
| SWR-001 | UC-01/S01 through UC-01/S04 | UC-01/S01/TC01, TC05; UC-01/S04/TC03, TC04, TC09 |
| SWR-002 | UC-01/S04, UC-03/S03, UC-04/S03 | UC-01/S04/TC01, TC14; every UC-03/S03 and UC-04/S03 test |
| SWR-003 | UC-02/S03, UC-03/S01, UC-04/S02 | every UC-02/S03 test; UC-03/S01/TC01; UC-04/S02/TC01 |
| SWR-004 | Every rejection and conflict slice | Every test whose expected outcome is invalid input, business rejection or conflict |
| SWR-005 | Existing-cart command slices in UC-01, UC-03 and UC-04 | UC-01/S04/TC07, TC08; UC-01/S05/TC01; UC-03/S04/TC01; UC-04/S04/TC01 |
| SWR-006 | UC-01/S05, UC-03/S04, UC-04/S04 | UC-01/S05/TC04; UC-03/S04/TC03; UC-04/S04/TC03 |
| SWR-007 | UC-01/S01, UC-01/S04 and every cart-result slice | UC-01/S01/TC01; UC-01/S04/TC05; cart-view tests contain no price data |
| SWR-008 | Every rejection and conflict slice | Every rejecting acceptance test asserts invalid input, business rejection or conflict explicitly |
| SWR-009 | Realization boundary | Review of public outcome contracts; localization is absent from cart behavior |
| SWR-010 | All 24 slices | All acceptance tests in this specification |
| SWR-011 | UC-02/S03, UC-02/S04, UC-05/S01 | every UC-02/S03 and UC-02/S04 test; UC-05/S01/TC03 |
| SWR-012 | Realization boundary and all persistent behavior | Retention inspection verifies there is no deletion, archival or expiry operation |
| SWR-013 | UC-01/S01, UC-01/S04, UC-03/S05, UC-04/S05 | UC-01/S01/TC01; UC-01/S04/TC07, TC08; UC-03/S05/TC02; UC-04/S05/TC02 |
| SWR-014 | UC-01/S05, UC-03/S04, UC-04/S04 | UC-01/S05/TC02, TC03; UC-03/S04/TC02; UC-04/S04/TC02 |
| SWR-015 | UC-01/S01, UC-01/S04 | every UC-01/S01 test; UC-01/S04/TC03 through TC05, TC08 |
| SWR-016 | Retry-capable command slices in UC-01, UC-03 and UC-04 | UC-01/S01/TC03, TC06 through TC08; UC-01/S02/TC03, TC04; UC-03/S01/TC03 through TC05; UC-04/S02/TC03 through TC05 |
| SWR-017 | UC-05/S01 through UC-05/S03 | every UC-05/S01, UC-05/S02 and UC-05/S03 test |
| SWR-018 | UC-01/S04, UC-02/S05, UC-03/S05, UC-04/S05, UC-05/S04 | UC-01/S04/TC05, TC12; UC-02/S05/TC02; UC-03/S05/TC04; UC-04/S05/TC04; UC-05/S04/TC02 |
| SWR-019 | Existing-cart command slices in UC-01, UC-03 and UC-04 | UC-01/S02/TC05; UC-01/S04/TC13; UC-03/S05/TC05; UC-04/S05/TC05 |
| SWR-020 | Every successful command slice and cart-view slice | UC-01/S01/TC01; UC-01/S02/TC02; UC-02/S04/TC01; successful retry tests |
| SWR-021 | UC-02/S02, UC-05/S01 | UC-02/S02/TC02; UC-05/S01/TC04 |
| SWR-022 | Every command rejection and conflict slice | UC-01/S05/TC05 through TC07; UC-03/S04/TC04; UC-04/S04/TC04; accepted retry tests |

## 7. Verification Rule

A use case is Flow Structure Understood, the state recorded in section 3, when:

- its primary actors and goal are agreed
- its basic flow and alternate flows are enumerated
- every alternate flow is assigned to at least one slice

A use-case slice is Prepared when:

- its actor and goal are clear
- the flows it covers are named in its Flow and Requirement Coverage entry
- every applicable system-wide requirement is associated with the slice in the
  System-Wide Requirement Traceability table
- it has at least one test case for every flow and requirement it names, and each
  test case defines observable success or rejection, including which outcome
  category of `SWR-008` a rejection falls into
- realization-boundary requirements that cannot be demonstrated by a black-box
  use-case test name their required inspection evidence in the traceability table
- any questions raised for the slice are answered or explicitly left out of the slice

A use-case slice is Verified when:

- every test case for the slice passes
- every applicable system-wide requirement is satisfied
- the release deployment satisfies the Trust Boundary constraint
- the tested system version is the one intended for release

The test cases in this document are black-box acceptance tests. They say what
the system must do for its actors. They do not prescribe how programmers should
unit test, integrate, store, deploy or monitor the implementation.

## 8. Realization Boundary

The following concerns are intentionally outside this requirements document and
belong in a separate use-case realization, architecture document or test
document:

- choice of programming language
- domain model shape
- persistence model
- concurrency implementation mechanism
- public transport protocol
- API schema technology
- database product
- migration tooling
- deployment topology
- component lifecycle framework
- source-code namespaces
- local task names
- automated test-suite names

A realization may later map each use-case slice to design elements, code,
database structures, API contracts and automated tests. That mapping is useful,
but it is the "how". This document is the "what".

## 9. Final Decisions

The following decisions close the previously open requirement questions for this
version of the spec:

1. There is no separate actor goal or request to create an empty cart. A cart is
   established by its first accepted product addition.
2. Every cart-changing command requires an actor-supplied command request
   identifier UUID. The first product addition requires no cart identifier or
   prior cart observation; the system generates and returns a unique cart
   identifier and the cart's first observation.
3. Repeating any accepted command with the same command request identifier and
   semantically equal complete input returns its exact original business result
   without accepting another change, even if the cart has subsequently changed.
   Representation details and delivery-specific transport metadata do not define
   command or result equality. Reuse for a different command or non-equal input
   is invalid. Invalid, rejected and conflicting attempts do not establish
   idempotency and do not consume their request identifiers.
4. An invalid or failed first product addition does not create a cart or
   accepted change history.
5. The cart owns product-identifier UUIDs and quantities only. Product prices
   belong outside the cart and are never accepted, stored, returned or used in
   cart decisions.
6. Cart identifiers are system-generated UUIDs. Product identifiers and command
   request identifiers are actor-supplied UUIDs. Command request identifiers
   belong to one global namespace across the backend.
7. A supplied cart identifier that does not identify an existing cart is
   invalid. The outcome does not distinguish malformed, unknown or otherwise
   non-identifying values.
8. Cancellation applies only to an existing open cart. An open cart whose
   product quantities have all been removed may be cancelled.
9. Cart viewing reports both confirmed and cancelled carts as closed and retains
   their final product identifiers and quantities. Support Users can inspect
   accepted change history to distinguish the closure reason.
10. Each public history entry reports cart revision, stable change type,
   acceptance time and business data without exposing internal storage
   identifiers or persistence metadata. The complete ordered history is returned
   without pagination in this version.
11. Change history is a support and audit use case, not a cart-management use
   case for Cart Managers.
12. After the first accepted addition, cart-changing requests are based on
   observations of that existing cart. There is no observation of a nonexistent
   cart.
13. A request to change an existing cart that is not based on its observation is
   invalid.
14. A concurrent-change conflict is defined by a stale observation of an
   existing cart, not by physical timing alone. Observations have no time-based
   expiry and do not lock or reserve carts.
15. If two logical commands with different command request identifiers are based
   on the same observation of an existing cart, at most one may be accepted.
   Identical delivery attempts sharing one identifier are one logical command and
   may all return its single successful result.
16. Two first-addition requests with different command request identifiers
   establish separate carts. Repeating an accepted first addition with the same
   request identifier does not.
17. After a concurrent-change conflict, the actor views the current cart and
   submits a new request based on that current state.
18. No quantified performance, availability or durability service levels are
   part of this requirements version. No deletion or archival behavior is
   provided; cart data is retained indefinitely.
19. A requested quantity must be between 1 and 1000; a requested quantity outside
   that range is invalid input. An otherwise valid addition that would make the
   held quantity for that product exceed 1000 is a business rejection.
20. Every request rejects input fields that are not declared for it.
21. Cart observation markers are system-authenticated, opaque and bound to the
   cart for which they were issued. Altered, fabricated and different-cart
   markers are invalid input. How the system authenticates a marker is a
   realization concern. Multiple different authentic markers may represent one
   cart revision, so marker byte equality is not required.
22. Every successful command returns the complete cart state and observation it
   produced. A replay returns that exact original result, even when it is no
   longer the current cart state.
23. Successful changes are immediately visible to cart-view and change-history
   requests.
24. Review Cart Change History and all of its slices are Must scope.
25. This version does not publish accepted cart changes to external consumers.
26. A valid product-identifier UUID is treated as an opaque reference. The cart
   does not check whether it exists in a product catalogue.
27. Carts, accepted changes, cart views and accepted-command results are retained
   indefinitely in this version.
28. There is no business limit on the number of distinct products or accepted
   changes in a cart. Change history consequently has no bounded maximum size.
29. Cart item pairs are returned in ascending product-identifier UUID order in
   every response that carries cart items, including cart views, independently of
   the order in which the products were added.
30. History revisions start at 1 and increase by one for each accepted change.
   Its stable change types and business data are fixed by `SWR-017`.
31. Outcome categories are decided by the fixed evaluation order in `SWR-022`:
   input validity, then accepted-command replay, then observation currency, then
   business rules. The first failing step alone determines the reported outcome,
   so a stale observation of a cart that has since been closed is reported as a
   concurrent-change conflict, not as a closed-cart business rejection. The
   observation-currency step does not apply to the first product addition.
32. Invalid input means the request is malformed or outside a declared range,
   independently of cart state. Business rejection means the request is well
   formed but the cart's state forbids it, which covers a closed cart, a removal
   that would take a held quantity below zero, confirmation of a cart with no
   product items, and an addition that would push a held quantity above 1000.
33. This version is permitted to run only behind a trusted boundary that supplies
   authentication and authorization. Cart identifiers and observation markers
   are not credentials.
34. A command request identifier denotes one logical command. Repeated identical
   delivery attempts can all return success while producing one accepted change.
35. If different otherwise acceptable command inputs concurrently use one
   previously unestablished global request identifier, exactly one logical
   command is accepted. Once established, every non-equal attempt receives
   invalid input.
