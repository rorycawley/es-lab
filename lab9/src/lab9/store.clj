(ns lab9.store
  "The store, with one key added: a global position.

  `:stream/version` orders one history. `:event/position` orders the whole
  log, across every stream, and is assigned by the store at append time — it
  is not the event's own property.")

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def truck-2 #uuid "0f1c2b3a-0000-4000-8000-000000000002")

(def load-vanilla-id #uuid "018f7a3e-0000-7000-8000-000000000901")
(def load-chocolate-id #uuid "018f7a3e-0000-7000-8000-000000000902")
(def sell-vanilla-1-id #uuid "018f7a3e-0000-7000-8000-000000000903")
(def sell-chocolate-1-id #uuid "018f7a3e-0000-7000-8000-000000000904")
(def sell-chocolate-2-id #uuid "018f7a3e-0000-7000-8000-000000000905")
(def sell-vanilla-2-id #uuid "018f7a3e-0000-7000-8000-000000000906")

(defn- event
  [event-id position stream-id version type data]
  {:event/id       event-id
   :event/type     type
   :event/position position
   :stream/id      stream-id
   :stream/version version
   :data           data})

(def log
  "Two trucks trading at once, so their events interleave.

  Read the columns: position runs 1..6 straight down; version is contiguous
  only once you filter to a single truck."
  [(event load-vanilla-id 1 truck-1 1 :truck-loaded
          {:flavour "vanilla" :quantity 2})
   (event load-chocolate-id 2 truck-2 1 :truck-loaded
          {:flavour "chocolate" :quantity 3})
   (event sell-vanilla-1-id 3 truck-1 2 :flavour-sold
          {:flavour "vanilla"})
   (event sell-chocolate-1-id 4 truck-2 2 :flavour-sold
          {:flavour "chocolate"})
   (event sell-chocolate-2-id 5 truck-2 3 :flavour-sold
          {:flavour "chocolate"})
   (event sell-vanilla-2-id 6 truck-1 3 :flavour-sold
          {:flavour "vanilla"})])

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
  "Model appending identified `events` to `stream-id` if the supplied log is
  still at `expected-version`.

  Event identity already exists and is preserved. This in-memory store assigns
  stream versions and global positions. A production store must enforce both
  number assignments and the batch write atomically."
  [log stream-id expected-version events]
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
                                :event/position (+ end 1 i)
                                :stream/id stream-id
                                :stream/version (+ actual 1 i)))
                       events))))
