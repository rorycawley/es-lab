(ns lab16.boundary-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab16.contention :as contention]
            [lab16.depot :as depot]
            [lab16.fleet :as fleet]
            [lab16.store :as store]
            [lab16.truck :as truck]))

(def fleet-stream #uuid "0f1c2b3a-0000-4000-8000-00000000f1ee")
(def depot-stream #uuid "0f1c2b3a-0000-4000-8000-0000000000d0")
(def trucks (mapv #(java.util.UUID/fromString
                    (format "0f1c2b3a-0000-4000-8000-00000000000%d" %))
                  (range 1 6)))

(def t0 #inst "2026-08-16T09:00:00.000-00:00")
(defn- gen-id [] (random-uuid))

(defn- command [type data]
  {:command/id (random-uuid) :command/type type
   :correlation-id (random-uuid) :data data})

(defn- apply-one [log stream-id decide replay cmd]
  ((contention/attempt log stream-id decide replay cmd) log gen-id t0))

;; ---------------------------------------------------------------------------
;; Design A — one stream for the whole fleet
;; ---------------------------------------------------------------------------

(def design-a
  (as-> [] log
    (apply-one log fleet-stream fleet/decide fleet/replay
               (command :stock-depot {:flavour "vanilla" :quantity 100}))
    (reduce (fn [l truck]
              (apply-one l fleet-stream fleet/decide fleet/replay
                         (command :load-truck {:truck-id truck :flavour "vanilla"
                                               :quantity 10})))
            log trucks)))

(deftest design-a-enforces-the-invariant-test
  (testing "the depot cannot be over-drawn, because one decide sees both sides"
    (is (= 50 (get-in (fleet/replay (store/stream design-a fleet-stream))
                      [:depot "vanilla"])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Depot cannot cover"
         (apply-one design-a fleet-stream fleet/decide fleet/replay
                    (command :load-truck {:truck-id (first trucks)
                                          :flavour "vanilla" :quantity 999}))))))

(deftest design-a-contends-on-every-sale-test
  (testing "five tills, five different trucks, one stream"
    (let [attempts (mapv (fn [t]
                           (contention/attempt design-a fleet-stream
                                               fleet/decide fleet/replay
                                               (command :buy-flavour
                                                        {:truck-id t :flavour "vanilla"})))
                         trucks)
          {:keys [conflicts log]} (contention/run-concurrently design-a attempts gen-id t0)]
      (is (= 4 conflicts) "one winner, four told to read again")
      (is (= 1 (- (count log) (count design-a))) "one sale recorded out of five"))))

;; ---------------------------------------------------------------------------
;; Design B — one stream per truck, and nothing owns the depot
;; ---------------------------------------------------------------------------

(def design-b
  (reduce (fn [l t]
            (apply-one l t truck/decide truck/replay
                       (command :load-truck {:flavour "vanilla" :quantity 10})))
          []
          trucks))

(deftest design-b-does-not-contend-test
  (testing "five tills, five streams, nobody waits"
    (let [attempts (mapv (fn [t]
                           (contention/attempt design-b t truck/decide truck/replay
                                               (command :buy-flavour {:flavour "vanilla"})))
                         trucks)
          {:keys [conflicts log]} (contention/run-concurrently design-b attempts gen-id t0)]
      (is (zero? conflicts))
      (is (= 5 (- (count log) (count design-b))) "all five sales recorded"))))

(deftest design-b-cannot-enforce-the-invariant-test
  (testing "load 150 cones out of a depot holding 100, and nothing objects"
    (let [overdrawn (reduce (fn [l t]
                              (apply-one l t truck/decide truck/replay
                                         (command :load-truck {:flavour "vanilla"
                                                               :quantity 30})))
                            design-b trucks)
          on-trucks (reduce + (map #(get (truck/replay (store/stream overdrawn %)) "vanilla" 0)
                                   trucks))]
      (is (= 200 on-trucks) "50 from the setup plus 150 more")
      (testing "no append failed, because no stream ever saw the total"
        (is (= (+ (count design-b) 5) (count overdrawn)))))))

;; ---------------------------------------------------------------------------
;; Design C — the depot owns its own stock
;; ---------------------------------------------------------------------------

(def design-c
  (apply-one [] depot-stream depot/decide depot/replay
             (command :stock-depot {:flavour "vanilla" :quantity 100})))

(deftest design-c-enforces-the-invariant-where-it-lives-test
  (let [issued (reduce (fn [l _]
                         (apply-one l depot-stream depot/decide depot/replay
                                    (command :issue-stock {:flavour "vanilla" :quantity 30})))
                       design-c
                       (range 3))]
    (is (= 10 (get (depot/replay (store/stream issued depot-stream)) "vanilla")))
    (testing "the fourth draw is refused, immediately, by the aggregate that owns it"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Depot cannot cover"
           (apply-one issued depot-stream depot/decide depot/replay
                      (command :issue-stock {:flavour "vanilla" :quantity 30})))))))

(deftest design-c-moves-contention-to-where-it-means-something-test
  (testing "sales contend nowhere — they never touch the depot"
    (let [stocked (reduce (fn [l t]
                            (apply-one l t truck/decide truck/replay
                                       (command :load-truck {:flavour "vanilla" :quantity 10})))
                          design-c trucks)
          attempts (mapv (fn [t]
                           (contention/attempt stocked t truck/decide truck/replay
                                               (command :buy-flavour {:flavour "vanilla"})))
                         trucks)]
      (is (zero? (:conflicts (contention/run-concurrently stocked attempts gen-id t0))))))
  (testing "restocks contend only on the depot, and that is a real signal"
    (let [attempts (mapv (fn [_]
                           (contention/attempt design-c depot-stream depot/decide depot/replay
                                               (command :issue-stock {:flavour "vanilla"
                                                                      :quantity 10})))
                         (range 5))]
      (is (= 4 (:conflicts (contention/run-concurrently design-c attempts gen-id t0)))))))

;; ---------------------------------------------------------------------------
;; The comparison, in one place
;; ---------------------------------------------------------------------------

(deftest the-three-designs-side-by-side-test
  (let [sales-conflicts
        (fn [log stream-fn decide replay cmd-for]
          (:conflicts (contention/run-concurrently
                       log
                       (mapv #(contention/attempt log (stream-fn %) decide replay (cmd-for %))
                             trucks)
                       gen-id t0)))
        a (sales-conflicts design-a (constantly fleet-stream) fleet/decide fleet/replay
                           #(command :buy-flavour {:truck-id % :flavour "vanilla"}))
        b (sales-conflicts design-b identity truck/decide truck/replay
                           (fn [_] (command :buy-flavour {:flavour "vanilla"})))]
    (is (= 4 a) "design A: one stream, four writers refused")
    (is (= 0 b) "design B and C: five streams, none refused")
    (testing "and only A and C can refuse an over-draw"
      (is (thrown? clojure.lang.ExceptionInfo
                   (apply-one design-a fleet-stream fleet/decide fleet/replay
                              (command :load-truck {:truck-id (first trucks)
                                                    :flavour "vanilla" :quantity 999}))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (apply-one design-c depot-stream depot/decide depot/replay
                              (command :issue-stock {:flavour "vanilla" :quantity 999}))))
      (is (some? (apply-one design-b (first trucks) truck/decide truck/replay
                            (command :load-truck {:flavour "vanilla" :quantity 999})))
          "design B accepts it without complaint"))))

(deftest replay-cost-follows-the-boundary-test
  (testing "design A folds the whole fleet's history to answer one truck's question"
    (is (= 6 (count (store/stream design-a fleet-stream)))))
  (testing "design B folds one truck's"
    (is (= 1 (count (store/stream design-b (first trucks)))))))
