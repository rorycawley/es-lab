(ns lab12.consumer
  "Another module entirely — the customer app. It has never heard of a truck
  aggregate, a stream, or an event store. It receives messages.

  Delivery is at-least-once, so it will sometimes receive the same fact twice.
  Handling that is its own responsibility, not the sender's: exactly-once
  *delivery* is not achievable across a network, but exactly-once *processing*
  is, and this is what it costs.

  \"Across a network\" is load-bearing — lab 20 shows the same move inside one
  database, where it is achievable.")

(def initial-model
  {:seen        #{}   ; the facts already applied, by :event/id
   :unavailable #{}}) ; what the app currently greys out

(defn- apply-message
  [model message]
  (case (:message/type message)
    :flavour-unavailable
    (update model :unavailable conj (get-in message [:payload :flavour]))

    ;; Not this module's business — purchasing handles it.
    model))

(defn receive
  "Apply a message unless this fact has already been applied.

  The check is on `:event/id` inside the payload, not on `:message/id`. A
  republished fact arrives in a *new* envelope, so deduplicating on the
  envelope would let it through (lab 4)."
  [model message]
  (let [event-id (get-in message [:payload :event/id])]
    (if (contains? (:seen model) event-id)
      model
      (-> model
          (apply-message message)
          (update :seen conj event-id)))))

(defn receive-all
  [model messages]
  (reduce receive model messages))
