(ns lab32.money
  "An amount of money, and the scale that makes it survive a round trip.

  Gotcha #10 says money is NUMERIC and decodes to BigDecimal. That is
  necessary and it is not sufficient, and the gap is the kind of thing that
  passes every test until an auditor asks a question.

  `clojure.data.json` writes a BigDecimal with `toString`, so `100M` is written
  as `100` -- a JSON integer, indistinguishable from a count. Reading it back
  with `:bigdec true` returns a Long, because `:bigdec` only governs numbers
  that *had* a decimal point. So an amount written as a BigDecimal can be read
  back as a Long, arithmetic on it silently still works, and the claim that
  money is BigDecimal end to end is quietly false in the middle of the system.

  Pinning the scale fixes it at the source: `100` is written as `100.0000`, and
  what comes back is what went in. The scale is 4 because the columns are
  `NUMERIC(19,4)`, and the two must agree -- otherwise the database rounds a
  value the application still believes it holds."
  (:import (java.math BigDecimal RoundingMode)))

(def scale
  "Minor units. Matches `NUMERIC(19,4)` in migrations 005 and the outbox."
  4)

(defn of
  "Coerce to a BigDecimal at the canonical scale.

  Doubles are refused rather than converted. A `double` that reached this
  function already lost the argument -- `(bigdec 0.1)` is
  0.1000000000000000055511151231257827, and rounding it to four places
  produces a number that looks right and came from a value that was not. The
  fix is upstream, at whatever parsed the input."
  ^BigDecimal [x]
  (cond
    (or (instance? Double x) (instance? Float x))
    (throw (ex-info "Money must not come from a floating point value"
                    {:reason :money-from-float :value x}))

    (nil? x)
    (throw (ex-info "Money is missing" {:reason :money-missing}))

    :else
    ;; UNNECESSARY, so that an amount carrying more precision than the currency
    ;; has is an error rather than a silent rounding. Somebody sending
    ;; 10.000049 means something, and quietly storing 10.0000 answers a
    ;; question they did not ask.
    (try
      (.setScale (bigdec x) scale RoundingMode/UNNECESSARY)
      (catch ArithmeticException _
        (throw (ex-info "Money carries more precision than the currency has"
                        {:reason :money-too-precise :value x :scale scale}))))))

(defn money?
  "Is this a BigDecimal at the canonical scale?

  Used by `money_test.clj` to walk everything the system reads back out of
  JSONB and assert that nothing turned into a Long or a Double on the way."
  [x]
  (and (decimal? x) (= scale (.scale ^BigDecimal x))))
