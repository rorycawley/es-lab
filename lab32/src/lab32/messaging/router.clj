(ns lab32.messaging.router
  "Which modules should receive which events, derived from what the modules
  declare rather than written down beside them.

  The build spec asks for \"a pure lookup: event-type -> [module]\", and a map
  literal would satisfy that. Lab 29 found the reason not to: a routing table
  maintained by hand is a second copy of a fact, and the first copy -- what
  each module says it publishes and consumes -- is the one that changes when
  somebody edits a module. Two copies of a fact drift, and this one drifts
  silently, because subscribing to an event nobody publishes looks exactly like
  an event that has not happened yet.

  So the table is folded out of the contracts, and a set of contracts that does
  not add up throws at construction. That moves the failure to system start,
  which is the only moment anybody is definitely looking."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(defn- duplicates [pairs]
  (->> pairs
       (group-by first)
       (keep (fn [[k vs]] (when (< 1 (count vs)) [k (mapv second vs)])))))

(defn- problems
  [contracts]
  (let [published (for [c contracts t (:publishes-events c)] [t (:module c)])
        consumed  (for [c contracts t (:consumes-events c)] [t (:module c)])
        event-set (set (map first published))]
    (concat
     ;; Two modules publishing one event type means the type has two meanings,
     ;; and a consumer cannot know which one it just received.
     (for [[t owners] (duplicates published)]
       (str "event " t " is published by " (str/join " and " owners)))
     ;; The one this catches in practice. A typo in a `:consumes-events` set is
     ;; a subscription that never fires, and nothing about a queue that stays
     ;; empty looks like an error.
     (for [[t module] consumed
           :when (not (event-set t))]
       (str "event " t " is consumed by " module " and published by nobody"))
     ;; A module cannot be its own consumer through the transport. It already
     ;; has the event -- it wrote it -- and routing it back would mean an
     ;; aggregate learning its own facts from a queue.
     (for [c contracts
           t (set/intersection (set (:publishes-events c)) (set (:consumes-events c)))]
       (str (:module c) " both publishes and consumes " t)))))

(defn router
  "Fold contracts into `{:events {type #{module}} :schemas {module schema}}`,
  or refuse to build."
  [contracts]
  (let [found (problems contracts)]
    (when (seq found)
      (throw (ex-info (str "The module contracts do not add up: " (str/join "; " found))
                      {:reason :incoherent-contracts :problems (vec found)})))
    {:events  (reduce (fn [acc [t module]] (update acc t (fnil conj #{}) module))
                      {}
                      (for [c contracts t (:consumes-events c)] [t (:module c)]))
     :schemas (into {} (for [c contracts] [(:module c) (:schema c)]))}))

(defn targets
  "Who should receive this event type.

  Zero is a legitimate answer, and that is the difference between an event and
  a command. Accounts publishes `:accounts/transaction-recorded` without
  knowing or caring that Compliance exists; if Compliance were removed
  tomorrow, this returns the empty set and the dispatcher marks the message
  processed having delivered it nowhere. That is correct. An event whose
  producer needed a consumer to exist would be a command with a friendlier
  name."
  [router event-type]
  (get-in router [:events event-type] #{}))

(defn schema-of
  [router module]
  (or (get-in router [:schemas module])
      (throw (ex-info "No schema is registered for this module"
                      {:reason :unknown-module :module module}))))

(defn consuming-modules
  "Every module that consumes something, in a stable order.

  Derived from the routing table rather than from the list of contracts: a
  module that publishes and consumes nothing has no inbox to drain, and
  starting a worker for it would poll an empty table forever."
  [router]
  (vec (sort (reduce set/union #{} (vals (:events router))))))
