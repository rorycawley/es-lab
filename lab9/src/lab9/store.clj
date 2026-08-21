(ns lab9.store
  "The store, with one key added: a global position.

  `:stream/version` orders one history. `:event/position` orders the whole
  log, across every stream, and is assigned by the store at append time — it
  is not the event's own property.")

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def truck-2 #uuid "0f1c2b3a-0000-4000-8000-000000000002")

(defn- event
  [position stream-id version type data]
  {:event/id       (random-uuid)
   :event/type     type
   :event/position position
   :stream/id      stream-id
   :stream/version version
   :data           data})

(def log
  "Two trucks trading at once, so their events interleave.

  Read the columns: position runs 1..6 straight down; version is contiguous
  only once you filter to a single truck."
  [(event 1 truck-1 1 :truck-loaded {:flavour "vanilla" :quantity 2})
   (event 2 truck-2 1 :truck-loaded {:flavour "chocolate" :quantity 3})
   (event 3 truck-1 2 :flavour-sold {:flavour "vanilla"})
   (event 4 truck-2 2 :flavour-sold {:flavour "chocolate"})
   (event 5 truck-2 3 :flavour-sold {:flavour "chocolate"})
   (event 6 truck-1 3 :flavour-sold {:flavour "vanilla"})])

(defn stream
  "The history of one truck (lab 7)."
  [log stream-id]
  (->> log
       (filter #(= stream-id (:stream/id %)))
       (sort-by :stream/version)
       vec))

(defn current-version
  [log stream-id]
  (->> (stream log stream-id)
       (map :stream/version)
       (apply max 0)))

(defn last-position
  [log]
  (->> log (map :event/position) (apply max 0)))

(defn since
  "Every event appended after global `position`, in order, across all streams.

  This is the only question `:stream/version` cannot answer: there is no
  single version meaning \"everything before here\", because every stream has
  its own."
  [log position]
  (->> log
       (filter #(> (:event/position %) position))
       (sort-by :event/position)
       vec))

(defn append
  "Append `events` to `stream-id` if it is still at `expected-version`,
  stamping identity, version, and the next global positions (lab 8)."
  [log stream-id expected-version gen-id events]
  (let [actual (current-version log stream-id)
        end    (last-position log)]
    (when-not (= expected-version actual)
      (throw (ex-info "Concurrent modification of stream"
                      {:stream/id        stream-id
                       :expected-version expected-version
                       :actual-version   actual})))
    (into log
          (map-indexed (fn [i event]
                         (assoc event
                                :event/id (gen-id)
                                :event/position (+ end 1 i)
                                :stream/id stream-id
                                :stream/version (+ actual 1 i)))
                       events))))
