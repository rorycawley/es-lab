(ns lab7.stream
  "Separating one history from another, and numbering it.

  The fleet has grown: there are two Ice Cream trucks now, and their events
  land in the same log.")

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def truck-2 #uuid "0f1c2b3a-0000-4000-8000-000000000002")

;; ---------------------------------------------------------------------------
;; Two new keys on the envelope.
;;
;;   :stream/id       whose history is this?
;;   :stream/version  where in that history does it sit?
;;
;; Version is numbered from 1 within each stream, with no gaps. It is not
;; unique across the log — every truck has a version 1 — and it means nothing
;; without a stream id beside it.
;; ---------------------------------------------------------------------------

(defn- event
  [stream-id version type data]
  {:event/id       (random-uuid)
   :event/type     type
   :stream/id      stream-id
   :stream/version version
   :data           data})

(def log
  "Every event from both trucks, in the order they were appended.

  Truck 1 loaded one vanilla and sold it. Truck 2 loaded three and sold one."
  [(event truck-1 1 :truck-loaded {:flavour :vanilla :quantity 1})
   (event truck-2 1 :truck-loaded {:flavour :vanilla :quantity 3})
   (event truck-1 2 :flavour-sold {:flavour :vanilla})
   (event truck-2 2 :flavour-sold {:flavour :vanilla})])

;; ---------------------------------------------------------------------------
;; Lab 6's fold, trimmed to stock. It is unchanged by any of this: a fold
;; still takes a plain sequence of events. What changes is which events.
;; ---------------------------------------------------------------------------

(def initial-state {})

(defmulti evolve (fn [_state event] (:event/type event)))

(defmethod evolve :truck-loaded
  [state event]
  (let [{:keys [flavour quantity]} (:data event)]
    (update state flavour (fnil + 0) quantity)))

(defmethod evolve :flavour-sold
  [state event]
  (update state (get-in event [:data :flavour]) (fnil dec 0)))

(defmethod evolve :default
  [state _event]
  state)

(defn replay
  [events]
  (reduce evolve initial-state events))

;; ---------------------------------------------------------------------------
;; :stream/id answers "fold which events?"
;; ---------------------------------------------------------------------------

(defn stream
  "The history of one truck, in the order it happened."
  [events stream-id]
  (->> events
       (filter #(= stream-id (:stream/id %)))
       (sort-by :stream/version)
       vec))

(defn state-of
  "The stock of one truck: select its history, then fold it."
  [events stream-id]
  (replay (stream events stream-id)))

;; ---------------------------------------------------------------------------
;; :stream/version answers "has anything happened since I looked?"
;; ---------------------------------------------------------------------------

(defn current-version
  "The version of the last event in a stream, or 0 for a stream that does not
  exist yet. This is the number a writer holds while it decides, and offers
  back when it appends."
  [events stream-id]
  (->> (stream events stream-id)
       (map :stream/version)
       (apply max 0)))

(defn append
  "Append `event` to `stream-id`, but only if the stream is still at
  `expected-version`.

  Returns the new log, or throws if the stream moved on underneath the writer.
  Nothing is locked between reading and writing — hence *optimistic*."
  [events stream-id expected-version event]
  (let [actual (current-version events stream-id)]
    (when-not (= expected-version actual)
      (throw (ex-info "Concurrent modification of stream"
                      {:stream/id        stream-id
                       :expected-version expected-version
                       :actual-version   actual})))
    (conj events (assoc event
                        :stream/id stream-id
                        :stream/version (inc actual)))))
