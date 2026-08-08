(ns cart.schema
  "Malli schemas describing cart.core's data — from outside it.

   No requires: schemas are data, and this namespace deliberately does not
   depend on cart.core (SPEC R1.2). Shared keywords are literals in both places.

   Nothing in cart.core refers to this namespace.")

;; ---------------------------------------------------------------------------
;; Shared
;; ---------------------------------------------------------------------------

(def Money
  "Minor units — cents, not euros (SPEC R2.5). A BigDecimal round-tripped
   through JSON becomes a double, and doubles drift."
  [:int {:min 0}])

(def PricedProductItem
  [:map
   [:product-id [:string {:min 1}]]
   [:quantity   [:int {:min 1}]]
   [:unit-price Money]])

;; Epoch millis rather than an instant type: unambiguous, and it round-trips
;; through anything. `inst?` would be too loose — it is satisfied by both
;; java.util.Date and java.time.Instant, so drift between them passes silently.
(def Timestamp [:int {:min 0}])

;; ---------------------------------------------------------------------------
;; Events — deliberately OPEN (SPEC R5.3)
;; ---------------------------------------------------------------------------
;;
;; No {:closed true} anywhere below. Deploy v2 with a new field, write events,
;; roll back to v1: a closed schema would reject those events on read and the
;; affected carts would become unloadable.

(def EventMetadata
  "Provenance about the request that produced the event, stored in
   message_metadata. Optional on the way in, because events written before it
   existed must still read back (SPEC R5.4). Open, because correlation and
   causation ids belong here later."
  [:map [:now Timestamp]])

(def ProductItemAdded
  [:map
   [:type [:= :cart.event/product-item-added]]
   [:data [:map
           [:cart-id      [:string {:min 1}]]
           [:product-item PricedProductItem]
           [:added-at     Timestamp]]]
   [:metadata {:optional true} EventMetadata]])

(def ProductItemRemoved
  [:map
   [:type [:= :cart.event/product-item-removed]]
   [:data [:map
           [:cart-id      [:string {:min 1}]]
           [:product-item PricedProductItem]
           [:removed-at   Timestamp]]]
   [:metadata {:optional true} EventMetadata]])

(def Confirmed
  [:map
   [:type [:= :cart.event/confirmed]]
   [:data [:map
           [:cart-id      [:string {:min 1}]]
           [:confirmed-at Timestamp]]]
   [:metadata {:optional true} EventMetadata]])

(def Cancelled
  [:map
   [:type [:= :cart.event/cancelled]]
   [:data [:map
           [:cart-id      [:string {:min 1}]]
           [:cancelled-at Timestamp]]]
   [:metadata {:optional true} EventMetadata]])

(def Event
  "The discriminated union. :multi dispatches on :type and validates only that
   branch — the runtime equivalent of a TypeScript union."
  [:multi {:dispatch :type}
   [:cart.event/product-item-added   ProductItemAdded]
   [:cart.event/product-item-removed ProductItemRemoved]
   [:cart.event/confirmed            Confirmed]
   [:cart.event/cancelled            Cancelled]])

;; ---------------------------------------------------------------------------
;; Commands — CLOSED
;; ---------------------------------------------------------------------------

(def Metadata [:map [:now Timestamp]])

(def AddProductItem
  [:map {:closed true}
   [:type [:= :cart.command/add-product-item]]
   [:data [:map {:closed true}
           [:cart-id      [:string {:min 1}]]
           [:product-item PricedProductItem]]]
   [:metadata Metadata]])

(def RemoveProductItem
  [:map {:closed true}
   [:type [:= :cart.command/remove-product-item]]
   [:data [:map {:closed true}
           [:cart-id      [:string {:min 1}]]
           [:product-item PricedProductItem]]]
   [:metadata Metadata]])

(def Confirm
  [:map {:closed true}
   [:type [:= :cart.command/confirm]]
   [:data [:map {:closed true} [:cart-id [:string {:min 1}]]]]
   [:metadata Metadata]])

(def Cancel
  [:map {:closed true}
   [:type [:= :cart.command/cancel]]
   [:data [:map {:closed true} [:cart-id [:string {:min 1}]]]]
   [:metadata Metadata]])

(def Command
  [:multi {:dispatch :type}
   [:cart.command/add-product-item    AddProductItem]
   [:cart.command/remove-product-item RemoveProductItem]
   [:cart.command/confirm             Confirm]
   [:cart.command/cancel              Cancel]])

;; ---------------------------------------------------------------------------
;; State — CLOSED
;; ---------------------------------------------------------------------------

(def ShoppingCart
  "{:closed true} catches the bug where an evolve method assocs onto old state
   and leaves :product-items dangling on a closed cart."
  [:multi {:dispatch :status}
   [:empty  [:map {:closed true} [:status [:= :empty]]]]
   [:opened [:map {:closed true}
             [:status        [:= :opened]]
             [:product-items [:map-of [:string {:min 1}] [:int {:min 1}]]]]]
   [:closed [:map {:closed true} [:status [:= :closed]]]]])

;; ---------------------------------------------------------------------------
;; Results
;; ---------------------------------------------------------------------------

(def StreamRead
  "What every EventStore implementation must hand back from read-stream.
   Asserted by the shared outbound contract suite against memory, SQLite and
   Postgres, so the three adapters cannot drift from this shape or from each
   other."
  [:map {:closed true}
   [:events  [:sequential Event]]
   [:version [:int {:min 0}]]
   [:exists? :boolean]])
