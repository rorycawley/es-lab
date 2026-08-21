(ns lab18.as-of-test
  (:require [clojure.test :refer [deftest is testing]]
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

(defn- append
  "Append events that OCCURRED at one moment and were RECORDED at another."
  [log occurred-at recorded-at cmd events]
  (let [base (store/append log truck-1 (store/current-version log truck-1)
                           gen-id occurred-at cmd events)
        new  (subvec base (count log))]
    (into log (mapv #(assoc-in % [:metadata :recorded-at] recorded-at) new))))

;; ---------------------------------------------------------------------------
;; The offline till from lab 1, made concrete.
;;
;;   1 Sep   loaded 10 vanilla        recorded the same day
;;   3 Sep   sold one                 recorded the same day
;;   3 Sep   sold one MORE            the till was offline; recorded on the 6th
;; ---------------------------------------------------------------------------

(def log
  (-> []
      (append sep-01 sep-01 (command :load-truck {:flavour "vanilla" :quantity 10})
              [{:event/type :truck-loaded :data {:flavour "vanilla" :quantity 10}}
               {:event/type :truck-loaded :data {:flavour "chocolate" :quantity 5}}])
      (append sep-03 sep-03 (command :buy-flavour {:flavour "vanilla"})
              [{:event/type :flavour-sold :data {:flavour "vanilla"}}])
      (append sep-03 sep-06 (command :buy-flavour {:flavour "vanilla"})
              [{:event/type :flavour-sold :data {:flavour "vanilla"}}])))

(defn- stock [events] (get-in (truck/replay events) [:stock "vanilla"]))
(defn- chocolate [events] (get-in (truck/replay events) [:stock "chocolate"]))

(deftest the-log-holds-one-late-arrival-test
  (is (= 4 (count log)))
  (let [late (last log)]
    (is (= sep-03 (:event/occurred-at late)) "the cone was sold on the 3rd")
    (is (= sep-06 (get-in late [:metadata :recorded-at])) "we heard on the 6th")))

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
          later  (append log sep-03 #inst "2026-09-20T10:00:00.000-00:00"
                         (command :buy-flavour {:flavour "vanilla"})
                         [{:event/type :flavour-sold :data {:flavour "vanilla"}}])]
      (is (= before (as-of/as-known-on later truck-1 sep-05))
          "a fact recorded in three weeks' time does not rewrite the 5th"))))

(deftest valid-time-is-not-stable-and-should-not-be-test
  (testing "learning something new about the 3rd changes what was true on the 5th"
    (let [before (stock (as-of/as-happened-by log truck-1 sep-05))
          later  (append log sep-03 #inst "2026-09-20T10:00:00.000-00:00"
                         (command :buy-flavour {:flavour "vanilla"})
                         [{:event/type :flavour-sold :data {:flavour "vanilla"}}])]
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
  (append log sep-03 sep-06
          (command :correct-sale {:from "vanilla" :to "chocolate"})
          [{:event/type :sale-corrected :data {:from "vanilla" :to "chocolate"}}]))

(deftest a-correction-is-a-new-fact-about-an-old-moment-test
  (testing "the till rang up vanilla; it was chocolate"
    (is (= 5 (count with-correction)))
    (is (= sep-03 (:event/occurred-at (last with-correction))))
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

;; ---------------------------------------------------------------------------
;; Reconstructing a decision
;; ---------------------------------------------------------------------------

(deftest a-past-decision-can-be-re-run-exactly-test
  (testing "decide is pure, so the state it saw plus the command reproduces it"
    (let [cmd   (command :buy-flavour {:flavour "vanilla"})
          state (as-of/state-before log truck-1 3 truck/replay)]
      (is (= 10 (get-in state [:stock "vanilla"])) "the truck as it was")
      (is (= [{:event/type :flavour-sold :data {:flavour "vanilla"}}]
             (:events (as-of/reconstruct truck/decide cmd state 1)))))))

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
