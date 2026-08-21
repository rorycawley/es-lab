(ns lab18.as-of-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab18.application :as application]
            [lab18.as-of :as as-of]
            [lab18.store :as store]
            [lab18.truck :as truck]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")

(def sep-01 #inst "2026-09-01T09:00:00.000-00:00")
(def sep-03 #inst "2026-09-03T14:00:00.000-00:00")
(def sep-05 #inst "2026-09-05T23:59:59.000-00:00")
(def sep-06 #inst "2026-09-06T10:00:00.000-00:00")

(defn- gen-id [] (random-uuid))

(defn- command [type data]
  {:command/id (random-uuid) :command/type type
   :correlation-id (random-uuid) :data data})

(defn- handle
  "Handle a command whose facts occur at one time and commit at another."
  [log occurred-at recorded-at cmd]
  (application/handle log truck-1 cmd 1 gen-id occurred-at recorded-at))

;; ---------------------------------------------------------------------------
;; The offline till from lab 1, made concrete.
;;
;;   1 Sep   loaded 10 vanilla        recorded the same day
;;   3 Sep   sold one                 recorded the same day
;;   3 Sep   sold one MORE            the till was offline; recorded on the 6th
;; ---------------------------------------------------------------------------

(def first-sale-command
  (command :buy-flavour {:flavour "vanilla"}))

(def late-sale-command
  (command :buy-flavour {:flavour "vanilla"}))

(def log
  (-> []
      (handle sep-01 sep-01
              (command :load-truck {:flavour "vanilla" :quantity 10}))
      (handle sep-01 sep-01
              (command :load-truck {:flavour "chocolate" :quantity 5}))
      (handle sep-03 sep-03
              first-sale-command)
      (handle sep-03 sep-06
              late-sale-command)))

(defn- stock [events] (get-in (truck/replay events) [:stock "vanilla"]))
(defn- chocolate [events] (get-in (truck/replay events) [:stock "chocolate"]))

(deftest the-log-holds-one-late-arrival-test
  (is (= 4 (count log)))
  (let [late (last log)]
    (is (= sep-03 (:event/occurred-at late)) "the cone was sold on the 3rd")
    (is (= sep-06 (get-in late [:metadata :recorded-at])) "we heard on the 6th")))

(deftest application-and-store-own-different-parts-of-the-envelope-test
  (let [event-id #uuid "018f7a3e-0000-7000-8000-000000001801"
        cmd      {:command/id     #uuid "0f1c2b3a-0000-4000-8000-000000001801"
                  :command/type   :load-truck
                  :correlation-id #uuid "cc79c083-0000-4000-8000-000000001801"
                  :data           {:flavour "vanilla" :quantity 10}}
        event    (first (application/handle [] truck-1 cmd 1
                                            (constantly event-id)
                                            sep-01 sep-03))]
    (testing "application-owned fact identity and context"
      (is (= event-id (:event/id event)))
      (is (= sep-01 (:event/occurred-at event)))
      (is (= (:command/id cmd) (get-in event [:metadata :causation-id])))
      (is (= (:correlation-id cmd) (get-in event [:metadata :correlation-id])))
      (is (= 0 (get-in event [:metadata :decision-stream-version])))
      (is (= 1 (get-in event [:metadata :rules-version]))))
    (testing "persistence-owned coordinates and transaction time"
      (is (= sep-03 (get-in event [:metadata :recorded-at])))
      (is (= 1 (:stream/version event)))
      (is (= 1 (:event/position event))))))

;; ---------------------------------------------------------------------------
;; Two axes, two right answers
;; ---------------------------------------------------------------------------

(deftest the-same-question-has-two-answers-test
  (testing "how much vanilla was on the truck on the 5th of September?"
    (is (= 9 (stock (as-of/as-known-on log truck-1 sep-05)))
        "what we believed on the 5th: one sale had reached us")
    (is (= 8 (stock (as-of/as-happened-by log truck-1 sep-05)))
        "what we now know was true on the 5th: two cones had gone")))

(deftest neither-answer-is-wrong-test
  (testing "they answer different questions, and each is right about its own"
    (let [believed (as-of/as-known-on log truck-1 sep-05)
          actual   (as-of/as-happened-by log truck-1 sep-05)]
      (is (= 3 (count believed)))
      (is (= 4 (count actual)))
      (testing "a single timestamp could only have produced one of them"
        (is (not= (stock believed) (stock actual)))))))

(deftest transaction-time-is-stable-test
  (testing "what we believed on the 5th cannot change, whatever arrives later"
    (let [before (as-of/as-known-on log truck-1 sep-05)
          later  (handle log sep-03 #inst "2026-09-20T10:00:00.000-00:00"
                         (command :buy-flavour {:flavour "vanilla"}))]
      (is (= before (as-of/as-known-on later truck-1 sep-05))
          "a fact recorded in three weeks' time does not rewrite the 5th"))))

(deftest valid-time-is-not-stable-and-should-not-be-test
  (testing "learning something new about the 3rd changes what was true on the 5th"
    (let [before (stock (as-of/as-happened-by log truck-1 sep-05))
          later  (handle log sep-03 #inst "2026-09-20T10:00:00.000-00:00"
                         (command :buy-flavour {:flavour "vanilla"}))]
      (is (= 8 before))
      (is (= 7 (stock (as-of/as-happened-by later truck-1 sep-05)))
          "not a bug — the truck really did have seven"))))

(deftest by-the-sixth-the-two-agree-test
  (testing "once everything has arrived, both axes give the same answer"
    (is (= (stock (as-of/as-known-on log truck-1 sep-06))
           (stock (as-of/as-happened-by log truck-1 sep-06))
           8))))

(deftest version-is-the-cursor-with-no-ambiguity-test
  (testing "when the question can be asked as a prefix, ask it that way"
    (is (= 10 (stock (as-of/up-to-version log truck-1 1))))
    (is (= 10 (stock (as-of/up-to-version log truck-1 2))) "the chocolate load")
    (is (= 9 (stock (as-of/up-to-version log truck-1 3))))
    (is (= 8 (stock (as-of/up-to-version log truck-1 4))))
    (is (= (stock (store/stream log truck-1))
           (stock (as-of/up-to-version log truck-1 99))))))

;; ---------------------------------------------------------------------------
;; Corrections
;; ---------------------------------------------------------------------------

(def with-correction
  (handle log sep-06 sep-06
          (command :correct-sale {:sale-id      (:event/id (last log))
                                  :from         "vanilla"
                                  :to           "chocolate"
                                  :effective-at sep-03})))

(deftest a-correction-is-a-new-fact-about-an-old-moment-test
  (testing "the till rang up vanilla; it was chocolate"
    (is (= 5 (count with-correction)))
    (is (= sep-06 (:event/occurred-at (last with-correction)))
        "the correction happened when it was made")
    (is (= sep-03 (as-of/valid-at (last with-correction)))
        "its effect belongs to the original sale date")
    (is (= sep-06 (get-in (last with-correction) [:metadata :recorded-at])))))

(deftest a-correction-changes-what-was-true-not-what-was-believed-test
  (let [believed (as-of/as-known-on with-correction truck-1 sep-05)
        actual   (as-of/as-happened-by with-correction truck-1 sep-05)]
    (testing "vanilla happens to match, for entirely different reasons"
      (is (= 9 (stock believed)) "one sale had reached us, uncorrected")
      (is (= 9 (stock actual)) "two sales happened, and one of them was chocolate"))
    (testing "chocolate is where the correction shows"
      (is (= 5 (chocolate believed)) "on the 5th we had not heard")
      (is (= 4 (chocolate actual)) "we now know a chocolate cone went on the 3rd"))
    (testing "and the original sale is still in the log, unaltered"
      (is (= 2 (count (filter #(= :flavour-sold (:event/type %)) with-correction)))))))

(deftest a-correction-identifies-one-sale-and-cannot-be-applied-twice-test
  (let [sale-id (:event/id (last log))
        cmd     (command :correct-sale {:sale-id      sale-id
                                        :from         "vanilla"
                                        :to           "chocolate"
                                        :effective-at sep-03})]
    (is (= sale-id (get-in (last with-correction) [:data :sale-id])))
    (try
      (handle with-correction sep-06 sep-06 cmd)
      (is false "the same sale must not be corrected twice")
      (catch clojure.lang.ExceptionInfo e
        (is (= :sale-already-corrected (:reason (ex-data e))))))))

;; ---------------------------------------------------------------------------
;; Reconstructing a decision
;; ---------------------------------------------------------------------------

(deftest a-past-decision-can-be-re-run-exactly-test
  (testing "retained inputs reproduce the original pure decision"
    (let [event            (nth log 2)
          expected-version (get-in event [:metadata :decision-stream-version])
          rules            (get-in event [:metadata :rules-version])
          state            (as-of/state-at-version log truck-1
                                                   expected-version truck/replay)]
      (is (= (:command/id first-sale-command)
             (get-in event [:metadata :causation-id])))
      (is (= 2 expected-version))
      (is (= 10 (get-in state [:stock "vanilla"])) "the truck as it was")
      (is (= [{:event/type :flavour-sold :data {:flavour "vanilla"}}]
             (:events (as-of/reconstruct truck/decide first-sale-command
                                         state rules)))))))

(deftest the-rules-must-be-the-rules-of-the-day-test
  (testing "a sale allowed under the old rules is refused under the new ones"
    (let [cmd   (command :buy-flavour {:flavour "vanilla"})
          state {:stock {"vanilla" 2}}]
      (is (:events (as-of/reconstruct truck/decide cmd state 1))
          "v1: two cones is enough")
      (is (:refused (as-of/reconstruct truck/decide cmd state truck/rules-version))
          "v2: two cones are the reserve, so this is refused")))
  (testing "so re-running an old decision under today's rules explains nothing"
    (let [cmd   (command :buy-flavour {:flavour "vanilla"})
          state {:stock {"vanilla" 2}}
          then  (as-of/reconstruct truck/decide cmd state 1)
          now   (as-of/reconstruct truck/decide cmd state truck/rules-version)]
      (is (not= then now)
          "the same command, the same state, a different answer — because the rules moved"))))

(deftest three-things-are-versioned-and-they-move-independently-test
  (testing "the event schema (lab 13), the fold (lab 17), and the rules (here)"
    (let [cmd   (command :buy-flavour {:flavour "vanilla"})
          state {:stock {"vanilla" 5}}]
      (testing "plenty of stock, so both rule versions agree"
        (is (= (:events (as-of/reconstruct truck/decide cmd state 1))
               (:events (as-of/reconstruct truck/decide cmd state 2)))))
      (testing "which is why a rules change can pass unnoticed for a long time"
        (is (some? (:events (as-of/reconstruct truck/decide cmd state 2))))))))

(deftest unexpected-decision-failures-are-not-reported-as-business-refusals-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown command or rules"
                        (as-of/reconstruct truck/decide
                                           (command :buy-flavour {:flavour "vanilla"})
                                           {:stock {"vanilla" 5}}
                                           999)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"programming failure"
                        (as-of/reconstruct
                         (fn [_command _state _rules]
                           (throw (ex-info "programming failure" {:reason :bug})))
                         (command :buy-flavour {:flavour "vanilla"})
                         {:stock {"vanilla" 5}}
                         1))))

(deftest invalid-domain-and-temporal-inputs-fail-explicitly-test
  (doseq [quantity [0 -1 1.5 nil]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Quantity must be"
                          (truck/decide
                           (command :load-truck {:flavour "vanilla"
                                                 :quantity quantity})
                           truck/initial-state
                           1))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown event type"
                        (truck/replay [{:event/type :freezer-failed}])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown command or rules"
                        (truck/decide (command :teleport {})
                                      truck/initial-state 1)))
  (doseq [bad-version [-1 1.5 nil]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Version must be"
                          (as-of/up-to-version log truck-1 bad-version))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not exist"
                        (as-of/state-before log truck-1 99 truck/replay)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cutoff must be"
                        (as-of/as-known-on log truck-1 "2026-09-05")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid event id"
                        (application/handle [] truck-1
                                            (command :load-truck
                                                     {:flavour "vanilla"
                                                      :quantity 10})
                                            1 (constantly "not-a-uuid")
                                            sep-01 sep-01)))
  (let [sale-id (:event/id (last log))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"effective time must match"
         (truck/decide
          (command :correct-sale {:sale-id      sale-id
                                  :from         "vanilla"
                                  :to           "chocolate"
                                  :effective-at sep-01})
          (truck/replay (store/stream log truck-1))
          1)))))
