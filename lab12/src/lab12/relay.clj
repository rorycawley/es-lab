(ns lab12.relay
  "The relay: reads the log, translates, publishes, advances its cursor.

  It is lab 9's projection with a different destination. A projection folds
  events into a read model; a relay folds them into messages someone else
  receives. Same checkpoint, same catch-up, same idempotence requirement."
  (:require [lab12.contract :as contract]
            [lab12.store :as store]))

;; ---------------------------------------------------------------------------
;; The broker, as a value. Real ones are not, but nothing here needs it to be
;; a process — a queue is a sequence of deliveries, and that is enough to show
;; what duplicates look like.
;; ---------------------------------------------------------------------------

(def empty-broker {:delivered []})

(defn deliver-message
  [broker message]
  (update broker :delivered conj message))

;; ---------------------------------------------------------------------------
;; A send stamps the delivery's own identity. Publishing the same fact twice
;; produces two :message/id values carrying one :event/id — which is exactly
;; the shape lab 4 argued for, and the reason a consumer must deduplicate on
;; the event id rather than on the envelope.
;; ---------------------------------------------------------------------------

(defn- stamp
  [gen-id event message]
  (assoc message
         :message/id (gen-id)
         :metadata   {:correlation-id (get-in event [:metadata :correlation-id])}))

(defn run-once
  "Publish everything appended since `checkpoint`.

  Returns the new broker and checkpoint. The checkpoint moves to the last
  position *read*, for the reason lab 10 gives."
  [log checkpoint broker gen-id]
  (let [batch (store/since log checkpoint)
        sends (for [event batch
                    message (contract/announce event)]
                (stamp gen-id event message))]
    {:broker     (reduce deliver-message broker sends)
     :checkpoint (->> batch (map :event/position) (apply max checkpoint))
     :sent       (vec sends)}))
