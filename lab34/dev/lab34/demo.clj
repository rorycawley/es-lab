(ns lab34.demo
  "Three applicants, three versions of the process, and what it costs to
  change it while they are inside it."
  (:gen-class)
  (:require [clojure.string :as str]
            [lab34.definition :as definition]
            [lab34.instance :as instance]
            [lab34.onboarding :as onboarding]
            [lab34.registry :as registry])
  (:import (java.time Instant)))

;; Fixed values, inlined rather than taken from the test fixture: `test/` is
;; not on this alias's path, and a demo that prints a different answer each
;; run would undercut the lab it is demonstrating.

(defn- at ^Instant [s] (Instant/parse s))

(def day-1 (at "2026-03-01T09:00:00Z"))
(def day-2 (at "2026-03-02T09:00:00Z"))

(def ada-id      #uuid "00000000-0000-4000-8000-0000000000a1")
(def grace-id    #uuid "00000000-0000-4000-8000-0000000000a2")
(def alan-id     #uuid "00000000-0000-4000-8000-0000000000a3")
(def newcomer-id #uuid "00000000-0000-4000-8000-0000000000a4")

(defn- event [type at]
  {:event/id #uuid "00000000-0000-4000-8000-0000000000e1"
   :event/type type :occurred-at at :data {}})

(defn- registry-with [& definitions]
  (reduce (fn [r d] (registry/publish r d [] onboarding/handled-commands))
          (registry/registry :onboarding)
          definitions))

(def rule "  ──────────────────────────────────────────────────────────────")

(defn- act [n title]
  (println)
  (println (str "  " n ". " title))
  (println rule))

(defn- show [label value]
  (println (format "     %-38s %s" label value)))

(defn- refusal
  "Run something expected to be refused, and return why."
  [f]
  (try (f) "not refused"
       (catch clojure.lang.ExceptionInfo e
         (str/join "; " (:problems (ex-data e))))))

(defn- where [instances resolve]
  (str/join ", " (for [i instances]
                   (str (subs (str (:process/id i)) 33) ": "
                        (name (instance/status i))
                        " (v" (:definition/version i) ")"
                        (when-not (instance/in-flight? i resolve) " ✓")))))

(defn -main [& _]
  (println)
  (println "  Lab 34 — a configurable process manager")
  (println)
  (println "  Account onboarding. Identity, then sanctions, then open the")
  (println "  account. Three applicants are part-way through it.")

  (let [r1       (registry-with onboarding/v1)
        resolve1 (registry/resolver r1)

        ;; Ada is waiting on sanctions. Grace was rejected on identity.
        ;; Alan is only just starting.
        ada   (-> (instance/start onboarding/v1 ada-id)
                  (instance/observe-all resolve1
                                        [(event :identity-verified day-1)]))
        grace (-> (instance/start onboarding/v1 grace-id)
                  (instance/observe-all resolve1
                                        [(event :identity-rejected day-1)]))
        alan  (instance/start onboarding/v1 alan-id)
        live  [ada grace alan]]

    (act 1 "The process, as data rather than as a defmulti")
    (show "states" (sort (definition/states onboarding/v1)))
    (show "on :identity-verified, from :awaiting-identity"
          (definition/next-state onboarding/v1 :awaiting-identity :identity-verified))
    (show ":approved issues" (definition/issued-by onboarding/v1 :approved))
    (println)
    (println "     A lookup, not an interpreter. Which is what lets the next")
    (println "     act happen at all.")

    (act 2 "Checked before it runs — five ways to be wrong")
    (doseq [[label broken]
            [["a transition to nowhere"
              (assoc-in onboarding/v1 [:states :awaiting-identity :on :identity-verified]
                        :awaiting-sanctionz)]
             ["a step nothing leads to"
              (assoc-in onboarding/v1 [:states :awaiting-documents] {:on {:got-them :approved}})]
             ["a step with no way out"
              (assoc-in onboarding/v1 [:states :awaiting-sanctions :on] {})]
             ["terminal, and also not"
              (assoc-in onboarding/v1 [:states :rejected :on] {:appeal :awaiting-sanctions})]
             ["a command nobody handles"
              (assoc-in onboarding/v1 [:states :approved :issue] :open-acount)]]]
      (show label (first (definition/problems broken onboarding/handled-commands))))
    (println)
    (println "     None of these needs an instance to discover it. That is the")
    (println "     guarantee lab 33's predicate DSL could not have.")

    (act 3 "Where everybody is")
    (show "in flight and finished" (where live resolve1))

    (act 4 "v2 ships: a sanctions hit now goes to a human")
    (let [r2       (registry/publish r1 onboarding/v2 live onboarding/handled-commands)
          resolve2 (registry/resolver r2)
          hit      (event :sanctions-hit day-2)
          ada'     (instance/observe ada resolve2 hit)
          newcomer (-> (instance/start onboarding/v2 newcomer-id)
                       (instance/observe-all resolve2
                                             [(event :identity-verified day-2)
                                              hit]))]
      (show "published" (registry/versions r2))
      (show "Ada, pinned to v1, gets a sanctions hit" (instance/status ada'))
      (show "a newcomer on v2 gets the same hit" (instance/status newcomer))
      (println)
      (println "     Same event, two answers, and both are correct. Ada is")
      (println "     following the process she started under.")

      (act 5 "v3 ships: identity and sanctions merge into one screen")
      ;; Everybody now: Ada rejected under v1, Grace rejected, Alan still
      ;; waiting on identity, and the newcomer with a human.
      (let [in-flight [ada' grace alan newcomer]]
        (show "attempting to publish v3"
              (refusal #(registry/publish r2 onboarding/v3 in-flight
                                          onboarding/handled-commands)))
        (println)
        (println "     Refused, and not because v3 is wrong — it is wrong *now*.")
        (println "     No config file can make that distinction, because no")
        (println "     config file knows who is standing in the room.")

        (act 6 "Release: the change and the migration, as one act")
        (show "forgetting somebody"
              (refusal #(registry/release r2 onboarding/v2 onboarding/v3
                                          {:awaiting-sanctions :awaiting-screening}
                                          in-flight onboarding/handled-commands)))
        (let [{r3 :registry moved :instances}
              (registry/release r2 onboarding/v2 onboarding/v3 onboarding/v3-migration
                                in-flight onboarding/handled-commands)]
          (show "the mapping somebody had to write" onboarding/v3-migration)
          (show "published" (registry/versions r3))
          (show "where they are now" (where moved (registry/resolver r3)))
          (let [travelled (first (filter #(seq (get-in % [:state :history])) moved))]
            (show "0a4's recorded path, after migrating"
                  (mapv :to (get-in travelled [:state :history])))
            (show "and the move itself, recorded"
                  (mapv (juxt :from-status :to-status)
                        (get-in travelled [:state :migrations]))))
          (println)
          (println "     0a4 went through :awaiting-sanctions. It did. v3 has")
          (println "     never heard of that state, and rewriting the history to")
          (println "     look like the new process would be falsifying the record.")
          (println "     The move is appended as its own fact instead.")
          (println)
          (println "     Note who did not move: the two finished applications")
          (println "     stay on v1. They are over, and dragging them onto a")
          (println "     process they already left would imply they are not.")
          (println))))))
