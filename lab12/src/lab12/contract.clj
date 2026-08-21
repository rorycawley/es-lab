(ns lab12.contract
  "The translation from a domain event to the integration messages that
  announce it — lab 3's third shape, finally being produced.

  This namespace *is* the published contract. Other modules depend on what it
  emits, and on nothing else in this system. That is the whole reason the
  translation lives here rather than in the domain: the domain stays free to
  be refactored, and the price of changing a contract is paid deliberately,
  in one file.")

;; ---------------------------------------------------------------------------
;; announce : event -> [message]
;;
;; Zero, one, or many (lab 5). Known private facts explicitly produce zero;
;; unknown semantics fail so a relay cannot checkpoint past a fact that might
;; need a contract.
;;
;; Note what the messages do NOT carry: a :message/id. That identifies a
;; delivery, not a fact, so it is stamped at send time — a redelivery of the
;; same fact gets a new one (lab 4). What travels unchanged is :event/id,
;; inside the payload, where the receiver reads it as data.
;; ---------------------------------------------------------------------------

(defmulti announce :event/type)

(defmethod announce :stock-depleted
  [event]
  (let [flavour  (get-in event [:data :flavour])
        truck-id (:stream/id event)
        event-id (:event/id event)]
    (when-not (uuid? event-id)
      (throw (ex-info "Invalid event id"
                      {:event/id event-id})))
    ;; One fact, two audiences, two contracts. Neither consumer is looking at
    ;; the domain event, so the two can evolve apart.
    [{:message/type :flavour-unavailable          ; the customer app: grey out a button
      :recipient    :customer-app
      :payload      {:event/id event-id
                     :truck-id truck-id
                     :flavour  flavour}}
     {:message/type :restock-required             ; purchasing: order more
      :recipient    :purchasing
      :payload      {:event/id event-id
                     :truck-id truck-id
                     :flavour  flavour}}]))

;; Selling a cone is nobody else's business. Neither is loading the truck.
(defmethod announce :flavour-sold
  [_event]
  [])

(defmethod announce :truck-loaded
  [_event]
  [])

(defmethod announce :default
  [event]
  (throw (ex-info "Unknown event type"
                  {:event/type (:event/type event)})))

(defn announce-all
  "Messages for a batch, preserving the supplied event and contract order."
  [events]
  (into [] (mapcat announce) events))
