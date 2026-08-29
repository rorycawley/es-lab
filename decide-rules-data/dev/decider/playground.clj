(ns decider.playground
  (:require
   [clojure.pprint :refer [pprint]]
   [decider.bundle :as bundle]
   [decider.core :as decider]
   [decider.fixtures :as fixtures]
   [decider.identity :as identity]))

(comment
  ;; Load every semantic bundle. Loading validates the DSL and adds
  ;; a deterministic SHA-256 content hash to the in-memory bundle.
  (def ebay
    (bundle/load "semantic-bundles/ebay-place-bid.edn"))

  (def airline
    (bundle/load "semantic-bundles/airline-reserve-seat.edn"))

  (def ticketmaster
    (bundle/load "semantic-bundles/ticketmaster-reserve-tickets.edn"))

  (def amazon
    (bundle/load "semantic-bundles/amazon-add-item.edn"))

  (def land-registry
    (bundle/load "semantic-bundles/land-registry-register-transfer.edn"))

  (def property-bidding
    (bundle/load "semantic-bundles/property-bidding-place-bid.edn"))

  (def secret-santa
    (bundle/load "semantic-bundles/secret-santa-assign-recipient.edn"))

  ;; Inspect immutable semantic identity.
  (identity/specification-ref ticketmaster)

  ;; Two ways to decide, and the difference is deliberate.
  ;;
  ;; `prepare-and-decide` takes a plain bundle and does the whole
  ;; validate-hash-compile job on every call. Good for a REPL, and used for most
  ;; of the examples below because it keeps them to one line.
  ;;
  ;; `prepare` does that work once and `decide` takes the result — tens of times
  ;; faster per decision, and the only sensible choice under load. `decide`
  ;; refuses a plain bundle rather than quietly preparing it, so the expensive
  ;; call is never the one that merely looks normal.
  (def prepared-ticketmaster
    (decider/prepare ticketmaster))

  ;; The prepared value holds compiled functions, so do not print it. This is
  ;; how to get the data back out.
  (decider/specification prepared-ticketmaster)

  ;; ---------------------------------------------------------------------------
  ;; eBay clone: place a bid
  ;; ---------------------------------------------------------------------------

  (def ebay-state
    {:auction-id "auction-1"
     :status :open
     :seller-id "seller-1"
     :starting-price 10000
     :minimum-increment 500
     :highest-bid {:bidder-id "buyer-1"
                   :amount 12000}})

  (def ebay-command
    {:command/type :place-bid
     :data {:bidder-id "buyer-2"
            :amount 12500}})

  (pprint (decider/prepare-and-decide ebay ebay-state ebay-command))

  ;; ---------------------------------------------------------------------------
  ;; Airline: reserve a seat
  ;; ---------------------------------------------------------------------------

  (def airline-state
    {:flight-id "EI123"
     :status :open
     :passenger-id->booking
     {"P123" {:status :confirmed
              :cabin :economy}}
     :seat-id->seat
     {"12A" {:status :available
             :cabin :economy}
      "1A" {:status :available
            :cabin :business}}})

  (def airline-command
    {:command/type :reserve-seat
     :data {:passenger-id "P123"
            :seat-id "12A"}})

  (pprint (decider/prepare-and-decide airline airline-state airline-command))

  ;; ---------------------------------------------------------------------------
  ;; Ticketmaster clone: reserve tickets
  ;; ---------------------------------------------------------------------------

  (def ticketmaster-state
    {:performance-id "oasis-dublin-2026"
     :sale-status :open
     :tickets-remaining 100
     :max-tickets-per-customer 4
     :customer-id->tickets-reserved
     {"customer-1" 2}})

  (def ticketmaster-command
    {:command/type :reserve-tickets
     :data {:customer-id "customer-1"
            :quantity 2}})

  ;; This section uses the prepared bundle defined above, which is the pattern
  ;; worth copying: prepare once, then vary state and command freely.
  (pprint
   (decider/decide prepared-ticketmaster
                   ticketmaster-state
                   ticketmaster-command))

  ;; Validly shaped request, rejected by a business rule.
  (pprint
   (decider/decide
    prepared-ticketmaster
    ticketmaster-state
    {:command/type :reserve-tickets
     :data {:customer-id "customer-1"
            :quantity 3}}))
  ;; => BR-4 / :ticket-limit-exceeded

  ;; Malformed request: this is NOT a business rejection.
  (pprint
   (decider/decide
    prepared-ticketmaster
    ticketmaster-state
    {:command/type :reserve-tickets
     :data {:customer-id "customer-1"
            :quantity "three"}}))
  ;; => :result/type :invalid-command

  ;; ---------------------------------------------------------------------------
  ;; Amazon clone: add an item to a basket
  ;; ---------------------------------------------------------------------------

  (def amazon-state
    {:basket-id "basket-1"
     :status :open
     :sku->product
     {"BOOK-1" {:purchasable? true
                :unit-price 1999
                :stock-available 10
                :max-per-order 5}}
     :sku->quantity
     {"BOOK-1" 2}})

  (def amazon-command
    {:command/type :add-item
     :data {:sku "BOOK-1"
            :quantity 2}})

  (pprint (decider/prepare-and-decide amazon amazon-state amazon-command))

  ;; ---------------------------------------------------------------------------
  ;; Land registry: register a transfer
  ;; ---------------------------------------------------------------------------

  (def land-state
    {:title-id "LT-123"
     :status :registered
     :registered-owner-id "owner-1"
     :restrictions #{}})

  (def land-command
    {:command/type :register-transfer
     :data {:transferor-id "owner-1"
            :transferee-id "owner-2"
            :instrument-executed? true
            :fee-paid? true}})

  (pprint (decider/prepare-and-decide land-registry land-state land-command))

  ;; ---------------------------------------------------------------------------
  ;; Property bidding: place a bid
  ;; ---------------------------------------------------------------------------

  (def property-state
    {:listing-id "property-123"
     :status :open
     :seller-id "seller-1"
     :minimum-bid 35000000
     :minimum-increment 100000
     :eligible-bidder-ids #{"buyer-1" "buyer-2"}
     :highest-bid {:bidder-id "buyer-1"
                   :amount 36000000}})

  (def property-command
    {:command/type :place-bid
     :data {:bidder-id "buyer-2"
            :amount 36100000}})

  (pprint
   (decider/prepare-and-decide property-bidding
                               property-state
                               property-command))

  ;; ---------------------------------------------------------------------------
  ;; Secret Santa: assign a recipient
  ;; ---------------------------------------------------------------------------

  (def santa-state
    {:exchange-id "christmas-2026"
     :status :assigning
     :participant-ids #{"alice" "bob" "carol" "dave"}
     :giver-id->recipient-id
     {"alice" "carol"}
     :giver-id->excluded-recipient-ids
     {"bob" #{"alice"}}})

  (def santa-command
    {:command/type :assign-recipient
     :data {:giver-id "bob"
            :recipient-id "dave"}})

  (pprint (decider/prepare-and-decide secret-santa santa-state santa-command))

  ;; Load and inspect all bundles.
  (mapv identity/specification-ref (fixtures/load-all))

  :rcf)
