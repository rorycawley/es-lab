(ns lab17.snapshot-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab17.application :as application]
            [lab17.snapshot :as snapshot]
            [lab17.store :as store]
            [lab17.truck :as truck]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def t0 #inst "2026-08-16T09:00:00.000-00:00")

(defn- gen-id [] (random-uuid))

(defn- command [type data]
  {:command/id (random-uuid) :command/type type
   :correlation-id (random-uuid) :data data})

(defn- handle [log cmd]
  (application/handle log truck-1 cmd gen-id t0))

(def fold
  {:evolve       truck/evolve
   :replay       truck/replay
   :fold-version truck/fold-version})

;; One load of 50, then 24 sales — a 25-event stream.
(def log
  (reduce (fn [l _] (handle l (command :buy-flavour {:flavour "vanilla"})))
          (handle [] (command :load-truck {:flavour "vanilla" :quantity 50}))
          (range 24)))

(def truth (truck/replay (store/stream log truck-1)))

(deftest the-stream-is-long-enough-to-be-worth-caching-test
  (is (= 25 (count (store/stream log truck-1))))
  (is (= {:stock {"vanilla" 26} :sold 24} truth)))

;; ---------------------------------------------------------------------------
;; 1. It changes cost, never answers
;; ---------------------------------------------------------------------------

(deftest a-snapshot-gives-the-same-answer-as-a-full-replay-test
  (let [snaps  (snapshot/take-snapshot snapshot/none log truck-1 fold)
        loaded (snapshot/load-state snaps log truck-1 fold)]
    (is (:from-snapshot? loaded))
    (is (= truth (:state loaded)))))

(deftest a-snapshot-plus-later-events-equals-a-full-replay-test
  (testing "snapshot taken mid-stream, then more sales land"
    (let [early  (vec (take 15 log))
          snaps  (snapshot/take-snapshot snapshot/none early truck-1 fold)
          loaded (snapshot/load-state snaps log truck-1 fold)]
      (is (= truth (:state loaded)))
      (is (= 10 (:folded loaded)) "only the events after the snapshot"))))

(deftest deleting-every-snapshot-changes-no-answer-test
  (testing "the property that makes a snapshot safe to lose"
    (let [snaps (snapshot/take-snapshot snapshot/none log truck-1 fold)
          gone  (snapshot/discard snaps truck-1)]
      (is (= (:state (snapshot/load-state snaps log truck-1 fold))
             (:state (snapshot/load-state gone log truck-1 fold))
             truth))
      (is (not (:from-snapshot? (snapshot/load-state gone log truck-1 fold)))))))

(deftest what-a-snapshot-buys-is-work-avoided-test
  (let [early (vec (take 15 log))
        snaps (snapshot/take-snapshot snapshot/none early truck-1 fold)]
    (is (= 25 (:folded (snapshot/load-state snapshot/none log truck-1 fold))))
    (is (= 10 (:folded (snapshot/load-state snaps log truck-1 fold))))
    (testing "and the answer is identical either way"
      (is (= (:state (snapshot/load-state snapshot/none log truck-1 fold))
             (:state (snapshot/load-state snaps log truck-1 fold)))))))

;; ---------------------------------------------------------------------------
;; 2. Snapshots version by the fold, not the event
;; ---------------------------------------------------------------------------

(def stale
  "A snapshot from before the fold moved stock under a `:stock` key. No event
  changed; the state shape did."
  {truck-1 {:state        {"vanilla" 26 :sold 24}
            :version      15
            :fold-version 1}})

(deftest a-snapshot-from-an-older-fold-is-not-usable-test
  (is (not (snapshot/usable? (get stale truck-1) truck/fold-version 25)))
  (is (snapshot/usable? (get (snapshot/take-snapshot snapshot/none log truck-1 fold) truck-1)
                        truck/fold-version
                        25)))

(deftest trusting-a-stale-fold-is-silently-wrong-test
  (testing "no exception, no warning — a plausible answer that is not the truth"
    (let [ignoring-the-check (reduce truck/evolve
                                     (get-in stale [truck-1 :state])
                                     (drop 15 (store/stream log truck-1)))]
      (is (not= truth ignoring-the-check))
      (is (= 34 (:sold ignoring-the-check)) "a number that looks fine and is not")
      (is (= {"vanilla" -10} (:stock ignoring-the-check))
          "the old top-level stock stranded, a new :stock counting from zero"))))

(deftest tolerant-folding-is-what-hides-it-test
  (testing "`fnil` is why the wrong shape produces garbage instead of a crash"
    (let [sale {:event/type :flavour-sold :data {:flavour "vanilla"}}]
      (is (= {"vanilla" 26 :sold 25 :stock {"vanilla" -1}}
             (truck/evolve {"vanilla" 26 :sold 24} sale)))
      (testing "and where the fold happens to be intolerant, it blows up instead"
        (is (thrown? NullPointerException
                     (truck/evolve {"vanilla" 26} sale))))
      (testing "which is luck, not a safety net — the fold-version check is the safety net"
        (is (not (snapshot/usable? (get stale truck-1) truck/fold-version 25)))))))

(deftest the-check-discards-it-and-rebuilds-test
  (let [loaded (snapshot/load-state stale log truck-1 fold)]
    (is (not (:from-snapshot? loaded)))
    (is (= truth (:state loaded)))
    (is (= 25 (:folded loaded)) "the full fold, which is the correct price")))

(deftest a-stale-snapshot-is-rebuilt-instead-of-upcast-test
  (testing "lab 13 upcasts events because they are facts; this is derived"
    (let [rebuilt (snapshot/take-snapshot stale log truck-1 fold)]
      (is (= truck/fold-version (get-in rebuilt [truck-1 :fold-version])))
      (is (= truth (get-in rebuilt [truck-1 :state]))))))

;; ---------------------------------------------------------------------------
;; 3. Read order
;; ---------------------------------------------------------------------------

(deftest folding-events-the-snapshot-already-contains-double-counts-test
  (testing "the bug that produces a number rather than an exception"
    (let [early    (vec (take 15 log))
          snaps    (snapshot/take-snapshot snapshot/none early truck-1 fold)
          state    (get-in snaps [truck-1 :state])
          correct  (:state (snapshot/load-state snaps log truck-1 fold))
          ;; wrong: fold the WHOLE stream on top of a snapshot of part of it
          doubled  (reduce truck/evolve state (store/stream log truck-1))]
      (is (= truth correct))
      (is (not= truth doubled))
      (is (= (+ (:sold truth) (:sold state)) (:sold doubled))
          "every event before the snapshot counted twice"))))

;; ---------------------------------------------------------------------------
;; 4. When to take one
;; ---------------------------------------------------------------------------

(deftest snapshotting-is-due-only-after-enough-events-test
  (let [short-log (vec (take 5 log))]
    (is (not (snapshot/due? snapshot/none short-log truck-1 truck/fold-version)))
    (is (snapshot/due? snapshot/none log truck-1 truck/fold-version)))
  (testing "and not again until the stream has moved on"
    (let [snaps (snapshot/take-snapshot snapshot/none log truck-1 fold)]
      (is (not (snapshot/due? snaps log truck-1 truck/fold-version))))))

(deftest taking-a-snapshot-twice-is-harmless-test
  (let [once  (snapshot/take-snapshot snapshot/none log truck-1 fold)
        twice (snapshot/take-snapshot once log truck-1 fold)]
    (is (= once twice))))

(deftest a-snapshot-is-never-required-test
  (testing "every question this lab asks is answerable with none at all"
    (is (= truth (:state (snapshot/load-state snapshot/none log truck-1 fold))))
    (is (= truth (truck/replay (store/stream log truck-1))))))

(deftest an-impossible-snapshot-position-is-rejected-test
  (let [from-the-future {truck-1 {:state        {:stock {"vanilla" 999}
                                                 :sold  999}
                                  :version      26
                                  :fold-version truck/fold-version}}
        loaded         (snapshot/load-state from-the-future log truck-1 fold)]
    (is (not (:from-snapshot? loaded)))
    (is (= truth (:state loaded)))
    (is (snapshot/due? from-the-future log truck-1 truck/fold-version)
        "the optional worker should replace a rejected snapshot immediately")))

(deftest malformed-snapshot-envelopes-are-rejected-test
  (doseq [bad [{:state truth :version -1 :fold-version truck/fold-version}
               {:state truth :version 1.5 :fold-version truck/fold-version}
               {:version 25 :fold-version truck/fold-version}
               {:state truth :version 25}]]
    (let [loaded (snapshot/load-state {truck-1 bad} log truck-1 fold)]
      (is (not (:from-snapshot? loaded)))
      (is (= truth (:state loaded))))))

(deftest an-old-fold-snapshot-is-due-immediately-test
  (is (snapshot/due? stale log truck-1 truck/fold-version)
      "otherwise every read would keep paying for a full replay"))

(deftest envelope-checks-cannot-detect-arbitrary-state-corruption-test
  (let [corrupt {truck-1 {:state        {:stock {"vanilla" 999}
                                         :sold  24}
                          :version      25
                          :fold-version truck/fold-version}}
        trusted (snapshot/load-state corrupt log truck-1 fold)
        rebuilt (snapshot/load-state (snapshot/discard corrupt truck-1)
                                     log truck-1 fold)]
    (is (:from-snapshot? trusted))
    (is (not= truth (:state trusted))
        "fold and position versions are compatibility checks, not checksums")
    (is (= truth (:state rebuilt))
        "because events remain authoritative, rejection or deletion recovers")))

(deftest application-identifies-facts-before-persistence-test
  (let [event-id #uuid "018f7a3e-0000-7000-8000-000000001701"
        cmd      {:command/id     #uuid "0f1c2b3a-0000-4000-8000-000000001701"
                  :command/type   :load-truck
                  :correlation-id #uuid "cc79c083-0000-4000-8000-000000001701"
                  :data           {:flavour "vanilla" :quantity 10}}
        event    (first (application/handle [] truck-1 cmd
                                            (constantly event-id) t0))]
    (is (= event-id (:event/id event)))
    (is (= t0 (:event/occurred-at event)))
    (is (= (:command/id cmd) (get-in event [:metadata :causation-id])))
    (is (= (:correlation-id cmd) (get-in event [:metadata :correlation-id])))
    (is (= 1 (:stream/version event)))
    (is (= 1 (:event/position event))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Invalid event id"
       (application/handle [] truck-1
                           (command :load-truck {:flavour "vanilla" :quantity 10})
                           (constantly "not-a-uuid") t0))))

(deftest domain-invariants-and-unknown-semantics-fail-directly-test
  (doseq [quantity [0 -1 1.5 nil]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Quantity must be"
         (truck/decide (command :load-truck
                                {:flavour "vanilla" :quantity quantity})
                       truck/initial-state))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown command type"
                        (truck/decide (command :teleport {})
                                      truck/initial-state)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown event type"
                        (truck/replay [{:event/type :freezer-failed}]))))
