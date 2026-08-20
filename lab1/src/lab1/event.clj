(ns lab1.event
  "Static examples of a Domain Event: 'flavour sold' from an Ice Cream truck.")

;; ---------------------------------------------------------------------------
;; The smallest thing that is recognisably an event: what kind of thing
;; happened, and what specifically happened.
;; ---------------------------------------------------------------------------

(def flavour-sold-vanilla
  {:event/type :flavour-sold
   :flavour    :vanilla})

(def flavour-sold-chocolate
  {:event/type :flavour-sold
   :flavour    :chocolate})

;; ---------------------------------------------------------------------------
;; The same fact, with the frame separated from what it constitutes.
;; ---------------------------------------------------------------------------

(def flavour-sold-vanilla-envelope
  {:event/type :flavour-sold
   :data       {:flavour :vanilla}})

(def examples
  [flavour-sold-vanilla
   flavour-sold-chocolate])

;; ---------------------------------------------------------------------------
;; Intent, not state delta.
;;
;; Both of these are true after the same sale. Only one of them says what the
;; business did; the other reduces the store to a change log.
;; ---------------------------------------------------------------------------

(def intent
  {:event/type :flavour-sold
   :data       {:flavour :vanilla}})

(def state-delta
  {:event/type :stock-level-changed
   :data       {:flavour :vanilla :to 2}})

;; ---------------------------------------------------------------------------
;; Granularity is irreversible.
;;
;; These two produce identical state — the price is 3.00 either way — and
;; answer different questions: one was a typo, the other a decision.
;; ---------------------------------------------------------------------------

(def price-corrected
  {:event/type :price-corrected
   :data       {:flavour :vanilla :price 3.00M}})

(def price-increased
  {:event/type :price-increased
   :data       {:flavour :vanilla :price 3.00M}})

;; The coarse name that covers both. Recording this instead loses the
;; distinction permanently: nothing here says which of the two it was.
(def price-changed
  {:event/type :price-changed
   :data       {:flavour :vanilla :price 3.00M}})

;; ---------------------------------------------------------------------------
;; No deletes. A mistaken sale is undone by a reversal, which leaves a trail
;; that the truck was once in that state.
;; ---------------------------------------------------------------------------

(def sale-reversed
  {:event/type :sale-reversed
   :data       {:flavour :vanilla :reason-code :rung-up-twice}})

;; ---------------------------------------------------------------------------
;; A recorded fact, with each question answered where it belongs.
;;
;;   :data       what a domain expert would recognise as part of the fact
;;   :metadata   things about the message rather than the fact
;;               (machine details — pod name, SQL timings — belong in neither)
;;
;; Two timestamps, because they come apart: the truck sold the cone at 14:32
;; and the till got around to saying so at 14:33.
;; ---------------------------------------------------------------------------

(def flavour-sold-vanilla-recorded
  {:event/type        :flavour-sold
   :event/occurred-at #inst "2026-08-16T14:32:07.000-00:00"
   :data              {:flavour  :vanilla
                       :truck-id #uuid "0f1c2b3a-0000-4000-8000-000000000001"}
   :metadata          {:recorded-at #inst "2026-08-16T14:33:01.000-00:00"
                       :actor       {:type :user :id "till-2"}}})

;; ---------------------------------------------------------------------------
;; Two identities, both real.
;;
;;   :event/id                     the handle you minted for this message
;;   (:stream/id, :stream/version) the natural key the store already contains
;;
;; The version is assigned rather than observed, which is why it identifies
;; the event without any of the fragility of deriving a key from its own data.
;; ---------------------------------------------------------------------------

(def flavour-sold-in-a-stream
  {:event/id       #uuid "018f7a3e-0000-7000-8000-000000000011"
   :event/type     :flavour-sold
   :stream/id      #uuid "0f1c2b3a-0000-4000-8000-000000000001"
   :stream/version 17
   :data           {:flavour :vanilla}})

;; ---------------------------------------------------------------------------
;; An actor is a kind as well as an id. A process manager is not a person, and
;; recording one as the other is a false record.
;;
;; The id is opaque on purpose: never a JWT, token, or credential. Append-only
;; storage cannot revoke one, it drags personal data into the store designed
;; to resist deletion, and it proves only that a token was pasted in.
;; ---------------------------------------------------------------------------

(def restocked-by-a-person
  {:event/type :truck-restocked
   :data       {:flavour :vanilla :quantity 20}
   :metadata   {:recorded-at #inst "2026-08-16T06:02:00.000-00:00"
                :actor       {:type :user :id "USR-83721"}}})

(def restocked-by-a-process
  {:event/type :truck-restocked
   :data       {:flavour :vanilla :quantity 20}
   :metadata   {:recorded-at #inst "2026-08-16T06:02:00.000-00:00"
                :actor       {:type :system :id :overnight-restock-process}}})
