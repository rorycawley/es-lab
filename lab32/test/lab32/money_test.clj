(ns lab32.money-test
  "Gotcha #10, before it reaches a database.

  Pure, and deliberately so: the bug this guards against is not a database bug
  and would not be caught by one."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [lab32.money :as money]))

(deftest an-amount-is-a-scaled-big-decimal-test
  (doseq [input [100 100M 100.0M "100" 100N]]
    (let [amount (money/of input)]
      (is (money/money? amount) (str input " did not become canonical money"))
      (is (= "100.0000" (str amount))))))

(deftest a-float-is-refused-rather-than-converted-test
  ;; The whole point. `(bigdec 0.1)` is 0.1000000000000000055511151231257827,
  ;; and rounding that to four places produces a number that looks right and
  ;; came from a value that was not. The fix belongs upstream, at whatever
  ;; parsed the input, so this refuses instead of papering over it.
  (doseq [input [0.1 (float 0.1) 10000.5]]
    (is (= :money-from-float
           (:reason (ex-data (try (money/of input)
                                  (catch clojure.lang.ExceptionInfo e e)))))
        (str input " was quietly converted"))))

(deftest more-precision-than-the-currency-has-is-refused-test
  (is (= :money-too-precise
         (:reason (ex-data (try (money/of 10.000049M)
                                (catch clojure.lang.ExceptionInfo e e))))))
  (testing "four places is exactly fine"
    (is (money/money? (money/of 10.0001M)))))

(deftest a-missing-amount-is-its-own-refusal-test
  (is (= :money-missing (:reason (ex-data (try (money/of nil)
                                               (catch clojure.lang.ExceptionInfo e e)))))))

;; ---------------------------------------------------------------------------
;; The round trip that motivated the whole namespace
;; ---------------------------------------------------------------------------

(deftest an-unscaled-big-decimal-comes-back-as-a-long-test
  ;; This is the bug, demonstrated. It is not hypothetical and it is not
  ;; caught by any type: `100M` is written as the JSON integer `100`, and
  ;; nothing on the way back can tell it from a count.
  (let [written (json/write-str {:amount 100M})]
    (is (= "{\"amount\":100}" written))
    (is (instance? Long (:amount (json/read-str written :key-fn keyword :bigdec true)))
        "a BigDecimal went in and a Long came out")))

(deftest a-scaled-amount-survives-test
  (let [written (json/write-str {:amount (money/of 100)})]
    (is (= "{\"amount\":100.0000}" written))
    (is (money/money? (:amount (json/read-str written :key-fn keyword :bigdec true))))))

(deftest reading-without-bigdec-loses-it-the-other-way-test
  ;; The second half of Gotcha #10. Even a correctly written amount decodes to
  ;; a Double unless the reader is told otherwise, so both halves are needed.
  (let [written (json/write-str {:amount (money/of 10000.50M)})]
    (is (instance? Double (:amount (json/read-str written :key-fn keyword))))
    (is (money/money? (:amount (json/read-str written :key-fn keyword :bigdec true))))))
