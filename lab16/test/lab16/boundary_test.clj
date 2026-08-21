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

(deftest design-a-makes-overlapping-sales-share-one-version-test
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
  (testing "five overlapping sales, five streams, no shared version"
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

(def design-c-stocked
  (reduce (fn [log truck-id]
            (let [issued (apply-one log depot-stream depot/decide depot/replay
                                    (command :issue-stock
                                             {:flavour "vanilla" :quantity 10}))]
              (apply-one issued truck-id truck/decide truck/replay
                         (command :load-truck
                                  {:flavour "vanilla" :quantity 10}))))
          design-c
          trucks))

(deftest design-c-accounts-for-both-sides-of-the-setup-test
  (let [at-depot (get (depot/replay (store/stream design-c-stocked depot-stream))
                      "vanilla")
        on-trucks (reduce + (map #(get (truck/replay
                                        (store/stream design-c-stocked %))
                                       "vanilla" 0)
                                 trucks))]
    (is (= 50 at-depot))
    (is (= 50 on-trucks))
    (is (= 100 (+ at-depot on-trucks))
        "the setup no longer creates truck stock outside the depot workflow")))

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
  (testing "sales on different trucks do not conflict with each other"
    (let [attempts (mapv (fn [t]
                           (contention/attempt design-c-stocked t truck/decide truck/replay
                                               (command :buy-flavour {:flavour "vanilla"})))
                         trucks)]
      (is (zero? (:conflicts (contention/run-concurrently design-c-stocked
                                                          attempts gen-id t0))))))
  (testing "stock issues contend only on the depot"
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
                           (fn [_] (command :buy-flavour {:flavour "vanilla"})))
        c (sales-conflicts design-c-stocked identity truck/decide truck/replay
                           (fn [_] (command :buy-flavour {:flavour "vanilla"})))]
    (is (= 4 a) "design A: one stream, four optimistic conflicts")
    (is (= 0 b) "design B: five streams, no optimistic conflicts")
    (is (= 0 c) "design C: five truck streams, no sale conflicts")
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
    (is (= 1 (count (store/stream design-b (first trucks))))))
  (testing "design C folds one truck plus the depot only for depot decisions"
    (is (= 1 (count (store/stream design-c-stocked (first trucks)))))
    (is (= 6 (count (store/stream design-c-stocked depot-stream))))))

(deftest invalid-domain-inputs-and-unknown-semantics-fail-test
  (doseq [[decide state command-type]
          [[fleet/decide fleet/initial-state :stock-depot]
           [fleet/decide fleet/initial-state :load-truck]
           [depot/decide depot/initial-state :stock-depot]
           [depot/decide depot/initial-state :issue-stock]
           [truck/decide truck/initial-state :load-truck]]
          quantity [0 -1 1.5 nil]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Quantity must be"
                          (decide (command command-type
                                           {:truck-id (first trucks)
                                            :flavour "vanilla"
                                            :quantity quantity})
                                  state))))
  (doseq [replay [fleet/replay depot/replay truck/replay]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown event type"
                          (replay [{:event/type :freezer-failed}]))))
  (doseq [[decide state] [[fleet/decide fleet/initial-state]
                          [depot/decide depot/initial-state]
                          [truck/decide truck/initial-state]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown command type"
                          (decide (command :teleport-stock {}) state)))))

(deftest the-application-identifies-before-the-store-and-validates-identity-test
  (let [event-id #uuid "018f7a3e-0000-7000-8000-000000002001"
        cmd      {:command/id     #uuid "0f1c2b3a-0000-4000-8000-000000001101"
                  :command/type   :stock-depot
                  :correlation-id #uuid "cc79c083-0000-4000-8000-000000000011"
                  :data           {:flavour "vanilla" :quantity 10}}
        event    (first ((contention/attempt [] depot-stream
                                             depot/decide depot/replay cmd)
                         [] (constantly event-id) t0))]
    (is (= event-id (:event/id event)))
    (is (= t0 (:event/occurred-at event)))
    (is (= (:command/id cmd) (get-in event [:metadata :causation-id])))
    (is (= (:correlation-id cmd) (get-in event [:metadata :correlation-id])))
    (is (= 1 (:stream/version event)))
    (is (= 1 (:event/position event))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid event id"
                        ((contention/attempt design-c depot-stream
                                             depot/decide depot/replay
                                             (command :issue-stock
                                                      {:flavour "vanilla"
                                                       :quantity 10}))
                         design-c (constantly "not-a-uuid") t0))))

(deftest contention-counting-does-not-hide-unexpected-failures-test
  (let [unexpected (fn [_log _gen-id _now]
                     (throw (ex-info "Database unavailable"
                                     {:reason :database-unavailable})))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Database unavailable"
                          (contention/run-concurrently [] [unexpected] gen-id t0)))))
