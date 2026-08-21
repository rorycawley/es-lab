(ns lab22.demo
  "A day in the life of the truck — the first thing in this repository that
  starts.

  Twenty labs are verified by tests and none of them runs. This one prints
  what happens, in order, so the machinery is watchable rather than merely
  asserted.

  Everything printed here is produced by the pure core; this namespace decides
  only *when* to print it. That is the same split as everywhere else, applied
  to the least important thing in the repository, because the split does not
  get a holiday for output."
  (:require [lab22.adapter.clock :as clock]
            [lab22.adapter.intake :as intake]
            [lab22.app :as app]
            [lab22.core.contract :as contract]
            [lab22.core.policy :as policy]
            [lab22.port :as port]
            [lab22.system :as system])
  (:gen-class))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")

(defn- say [& parts] (println (apply str parts)))
(defn- rule [] (say "  " (apply str (repeat 62 "─"))))

(defn- command [type data]
  {:command/id (random-uuid) :command/type type :data data})

(defn- show-events [events]
  (doseq [e events]
    (say "     event   v" (:stream/version e) "  " (name (:event/type e))
         "  " (pr-str (:data e)))))

(defn- show-stock [app]
  (say "     stock   " (pr-str (app/stock app truck-1))))

(defn run
  "The story. Returns the final state so a test can assert on it."
  [app]
  (say)
  (say "  An Ice Cream truck, one morning")
  (rule)

  (say "  1. The depot loads two vanilla cones.")
  (show-events (app/handle app truck-1 (command :load-truck {:flavour "vanilla" :quantity 2})))
  (show-stock app)

  (say)
  (say "  2. A customer buys one.")
  (show-events (app/handle app truck-1 (command :buy-flavour {:flavour "vanilla"})))
  (show-stock app)

  (say)
  (say "  3. Another buys the last one. Selling it is TWO facts (lab 5).")
  (show-events (app/handle app truck-1 (command :buy-flavour {:flavour "vanilla"})))
  (show-stock app)

  (say)
  (say "  4. Two ways to say no, and they are not the same (lab 2, lab 22).")
  (let [malformed (intake/submit app truck-1 {:type :buy-flavour :data {:flavour "tarmac"}})
        refused   (intake/submit app truck-1 {:type :buy-flavour :data {:flavour "vanilla"}})]
    (say "     tarmac   → " (name (:rejected malformed))
         "   the schema refused it; the domain never saw it")
    (say "     vanilla  → " (name (:rejected refused))
         "     well-formed, and the truck is empty"))

  (say)
  (say "  5. Other modules are told — but only about the depletion (lab 12).")
  (doseq [m (port/pending (:outbox app))]
    (say "     message " (contract/describe m)))

  (say)
  (say "  6. A policy notices the depletion and asks for a restock (lab 10).")
  (let [{:keys [commands events]} (app/react app 0 truck-1)]
    (doseq [c commands]
      (say "     command " (name (:command/type c))
           "  " (pr-str (select-keys (:data c) [:flavour :quantity]))))
    (show-events events))
  (show-stock app)

  (say)
  (say "  7. Nothing new happened, so the reactor does nothing (lab 10).")
  (let [{:keys [commands]} (app/react app 99 truck-1)]
    (say "     commands issued: " (count commands)))

  (rule)
  (say "  Every decision above was a pure function. This namespace only")
  (say "  chose when to print.")
  (say)
  (app/stock app truck-1))

(defn -main [& _]
  (let [sys (system/start
             (system/in-memory {:clock (clock/fixed-clock #inst "2026-09-01T09:00:00.000-00:00")
                                :ids   (clock/counting-ids)}))]
    (try
      (run (system/app sys))
      (say "  (restock quantity is " policy/restock-quantity ", from the policy)")
      (say)
      (finally (system/stop sys)))))
