(ns lab5.cardinality
  "Examples of how many events a command produces, and how many messages an
  event produces, for an Ice Cream truck. The answer is not always one.")

;; ---------------------------------------------------------------------------
;; A command produces ZERO events.
;;
;; The request was refused. Nothing happened, so there is no fact to record.
;; A refusal is not an event: it is the absence of one.
;; ---------------------------------------------------------------------------

(def buy-pistachio
  {:command/id   #uuid "018f7a3d-0000-7000-8000-0000000000b1"
   :command/type :buy-flavour
   :data         {:flavour "pistachio"}})

(def refused
  "Pistachio sold out this morning. The customer is told no."
  {:command buy-pistachio
   :events  []})

;; ---------------------------------------------------------------------------
;; A command produces ONE event. The common case, and the only one the
;; earlier labs showed.
;; ---------------------------------------------------------------------------

(def buy-vanilla
  {:command/id   #uuid "018f7a3d-0000-7000-8000-0000000000b2"
   :command/type :buy-flavour
   :data         {:flavour "vanilla"}})

(def ordinary-sale
  {:command buy-vanilla
   ;; These are decision outcomes, not recorded envelopes. Lab 8's store will
   ;; stamp identity, stream and version at the recording boundary.
   :events  [{:event/type :flavour-sold
              :data       {:flavour "vanilla"}}]})

;; ---------------------------------------------------------------------------
;; A command produces MANY events.
;;
;; Selling the last chocolate cone is two facts, not one. Both are true, both
;; are worth recording, both were caused by the same request.
;;
;; A vector, not a set: the order is appended and replayed as written, and the
;; facts are emitted in the order they became true. The sale caused the
;; depletion, so it comes first.
;; ---------------------------------------------------------------------------

(def buy-chocolate
  {:command/id   #uuid "018f7a3d-0000-7000-8000-0000000000b3"
   :command/type :buy-flavour
   :data         {:flavour "chocolate"}})

(def last-cone-sale
  {:command buy-chocolate
   :events  [{:event/type :flavour-sold
              :data       {:flavour "chocolate"}}
             {:event/type :stock-depleted
              :data       {:flavour "chocolate"}}]})

(def decisions
  [refused
   ordinary-sale
   last-cone-sale])

;; ---------------------------------------------------------------------------
;; An event produces ZERO messages.
;;
;; Most facts are nobody else's business. Publishing is a decision to expose a
;; fact as a contract, made once per event type, and the default is no.
;; ---------------------------------------------------------------------------

(def flavour-sold-chocolate
  {:event/id   #uuid "018f7a3e-0000-7000-8000-0000000000e2"
   :event/type :flavour-sold
   :data       {:flavour "chocolate"}})

(def stock-depleted-chocolate
  {:event/id   #uuid "018f7a3e-0000-7000-8000-0000000000e3"
   :event/type :stock-depleted
   :data       {:flavour "chocolate"}})

(def kept-private
  "Sales are the truck's own business; no other module is told."
  {:event    flavour-sold-chocolate
   :messages []})

;; ---------------------------------------------------------------------------
;; An event produces ONE message.
;;
;; Opening the truck matters to the customer app and nobody else.
;; ---------------------------------------------------------------------------

(def truck-opened-smithfield
  {:event/id   #uuid "018f7a3e-0000-7000-8000-0000000000e4"
   :event/type :truck-opened
   :data       {:area "Smithfield"}})

(def announced-once
  {:event    truck-opened-smithfield
   :messages [{:message/type :truck-opened
               :payload      {:event/id (:event/id truck-opened-smithfield)
                              :area     "Smithfield"}}]})

;; ---------------------------------------------------------------------------
;; An event produces MANY messages.
;;
;; Two modules care that stock ran out, and they want different contracts:
;; purchasing needs to reorder, the customer app needs to grey out a button.
;; Same fact, two envelopes, two audiences.
;; ---------------------------------------------------------------------------

(def fanned-out
  {:event    stock-depleted-chocolate
   :messages [{:message/type :flavour-unavailable
               :payload      {:event/id (:event/id stock-depleted-chocolate)
                              :flavour  "chocolate"}}
              {:message/type :restock-required
               :payload      {:event/id (:event/id stock-depleted-chocolate)
                              :flavour  "chocolate"}}]})

;; Publication decides the contracts first. The publisher then creates one
;; transport envelope per proposal, which is where delivery identity belongs.
(def fanned-out-envelopes
  (mapv (fn [message message-id] (assoc message :message/id message-id))
        (:messages fanned-out)
        [#uuid "018f7a3f-0000-7000-8000-0000000000f1"
         #uuid "018f7a3f-0000-7000-8000-0000000000f2"]))

(def publications
  [kept-private
   announced-once
   fanned-out])

;; ---------------------------------------------------------------------------
;; Counting.
;; ---------------------------------------------------------------------------

(defn events-produced
  "How many facts this decision recorded. Zero, one, or many."
  [decision]
  (count (:events decision)))

(defn messages-produced
  "How many deliveries this fact caused. Zero, one, or many."
  [publication]
  (count (:messages publication)))
