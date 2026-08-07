(ns cart.app.handle
  "Application service. The only place the pure core meets the impure world:

     read (impure) -> fold (pure) -> decide (pure) -> append (impure)"
  (:require [cart.core :as core]
            [cart.port.event-store :as store]))

(def default-retry
  "SPEC R4.8. Matching Emmett's defaults."
  {:retries 3 :min-timeout 100 :factor 1.5})

(defn- sleep!
  "=> true if the backoff completed, false if the thread was interrupted.

   Restores the interrupt flag rather than swallowing it: a caller cancelling
   this thread is entitled to see that cancellation, and clearing the flag
   strands it. The retry loop stops and surfaces the conflict it already has,
   which is a real answer — better than throwing away the work."
  [ms]
  (try
    (Thread/sleep (long ms))
    true
    (catch InterruptedException _
      (.interrupt (Thread/currentThread))
      false)))

(defn- stamp
  "Carries the command's metadata onto the events it produced, so the store has
   something to put in message_metadata.

   Done here, not in decide: metadata is provenance about the request, not a
   fact about the cart, and cart.core must stay free of it."
  [events metadata]
  (if (seq metadata)
    (mapv #(assoc % :metadata metadata) events)
    (vec events)))

(defn- attempt
  [event-store stream-id command expected-version]
  (let [{:keys [events version exists?]} (store/read-stream event-store stream-id)
        state (core/fold events)
        [outcome payload] (core/decide command state)]
    (case outcome
      :error [:error payload]
      :ok    (if (empty? payload)
               ;; SPEC R4.7: no events means no write at all — not "append
               ;; zero events". No version bump, no possible conflict.
               [:ok {:events [] :version version :created-new-stream? false}]
               (let [expected (or expected-version
                                  (if exists? version :stream-does-not-exist))
                     stamped  (stamp payload (:metadata command))
                     [res data] (store/append-to-stream event-store stream-id
                                                        stamped expected)]
                 (case res
                   :ok       [:ok (assoc data :events stamped)]
                   :conflict [:conflict data]))))))

(defn handle-command
  "=> [:ok {...}] | [:error {:reason kw}] | [:conflict {:expected n :current n}]

   deps: {:event-store <EventStore> :retry <map or nil>}

   Retry (when configured) re-runs the WHOLE cycle against freshly read state,
   never just the append — retrying the append alone would reapply a decision
   made against state that no longer exists. This is only safe because decide
   is pure: resolve any external data before calling, not inside the loop.

   Retry applies only when the expected version is DERIVED from the fresh read.
   A caller-pinned expected-version is re-applied unchanged on every attempt,
   against state that has already moved past it, so each attempt conflicts
   identically — retrying it just adds latency to a foregone answer. A pinned
   version is the caller saying \"this write is valid only against exactly this
   version\", which is precisely the intent a retry would violate.

   An interrupt during backoff stops the loop and returns the conflict, with
   the interrupt flag restored for the caller."
  ([deps stream-id command]
   (handle-command deps stream-id command nil))
  ([{:keys [event-store retry]} stream-id command expected-version]
   (let [{:keys [retries min-timeout factor]} (merge default-retry retry)
         retryable? (and retry (nil? expected-version))]
     (loop [n 0]
       (let [result (attempt event-store stream-id command expected-version)]
         (if (and (= :conflict (first result))
                  retryable?
                  (< n retries)
                  (sleep! (* min-timeout (Math/pow factor n))))
           (recur (inc n))
           result))))))
