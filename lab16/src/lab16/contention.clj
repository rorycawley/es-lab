(ns lab16.contention
  "Measuring what a boundary costs.

  Optimistic concurrency (lab 7) refuses an append whose expected version has
  moved. So the question 'how much will this design contend?' has a precise
  answer: **how many concurrent writers target the same stream?**

  That is a structural property of the boundary, decided at design time and
  not by luck. This namespace counts it."
  (:require [lab16.store :as store]))

(defn attempt
  "One writer: read a stream, decide against it, and hold the result until
  it tries to append.

  The version it read is captured *now* — which is exactly the window
  optimistic concurrency exists to police."
  [log stream-id decide replay command]
  (let [version (store/current-version log stream-id)
        state   (replay (store/stream log stream-id))
        events  (decide command state)]
    (fn [current-log gen-id now]
      (store/append current-log stream-id version gen-id now command events))))

(defn run-concurrently
  "Every writer reads the same log, then their appends land one after another.

  A faithful model of the race: all of them decided against the same state,
  and the store adjudicates."
  [log attempts gen-id now]
  (reduce (fn [{:keys [log] :as acc} attempt]
            (try
              (assoc acc :log (attempt log gen-id now))
              (catch clojure.lang.ExceptionInfo e
                (if (= "Concurrent modification of stream" (ex-message e))
                  (update acc :conflicts inc)
                  (throw e)))))
          {:log log :conflicts 0}
          attempts))
