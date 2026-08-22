(ns lab31.performance.system
  "The user journey on either side of a boundary with controlled fixed latency.")

(defn simulated-gateway
  "Wrap lookup functions in a controlled per-call wait. This is a latency model,
  not a claim about any particular network."
  [lookup-one lookup-many latency-ms]
  (let [calls (atom {:one 0 :many 0})
        wait! #(Thread/sleep (long latency-ms))]
    {:fetch-one (fn [registration]
                  (swap! calls update :one inc)
                  (wait!)
                  (lookup-one registration))
     :fetch-many (fn [registrations]
                   (swap! calls update :many inc)
                   (wait!)
                   (lookup-many registrations))
     :calls calls}))

(defn local-journey [lookup-many registrations]
  {:found       (lookup-many registrations)
   :round-trips 0})

(defn chatty-journey [gateway registrations]
  {:found       (mapv #((:fetch-one gateway) %) registrations)
   :round-trips (count registrations)})

(defn batched-journey [gateway registrations]
  {:found       ((:fetch-many gateway) registrations)
   :round-trips 1})
