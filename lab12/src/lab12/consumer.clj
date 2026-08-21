(ns lab12.consumer
  "Another module entirely — the customer app. It has never heard of a truck
  aggregate, a stream, or an event store. It receives messages.

  Delivery is at-least-once, so it will sometimes receive the same fact twice.
  Handling that is its own responsibility, not the sender's. This immutable
  model keeps its seen set and effect in one value; a production consumer
  needs a durable inbox committed atomically with the effect (lab 20).

  \"Across a network\" is load-bearing — lab 20 shows the same move inside one
  database, where it is achievable.")

(def initial-model
  {:seen        #{}   ; the facts already applied, by :event/id
   :unavailable #{}}) ; what the app currently greys out

(def recipient :customer-app)

(defn- apply-message
  [model message]
  (case (:message/type message)
    :flavour-unavailable
    (update model :unavailable conj (get-in message [:payload :flavour]))

    (throw (ex-info "Unknown message type"
                    {:message/type (:message/type message)}))))

(defn receive
  "Apply this recipient's message unless the fact was already applied.

  The check is on `:event/id` inside the payload, not on `:message/id`. A
  republished fact arrives in a *new* envelope, so deduplicating on the
  envelope would let it through (lab 4). Messages for other recipients do not
  touch this model or its seen set."
  [model message]
  (if (not= recipient (:recipient message))
    model
    (let [event-id (get-in message [:payload :event/id])]
      (when-not (uuid? event-id)
        (throw (ex-info "Invalid event id"
                        {:event/id event-id})))
      (if (contains? (:seen model) event-id)
        model
        (-> model
            (apply-message message)
            (update :seen conj event-id))))))

(defn receive-all
  [model messages]
  (reduce receive model messages))
