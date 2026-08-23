(ns lab32.messaging.failure
  "What gets written into `last_error`.

  One namespace rather than the same two lines in the dispatcher and the
  worker, and one concept: a durable record of why something did not work,
  written for whoever finds the dead letter weeks later."
  (:require [clojure.string :as str]))

(def ^:private max-length
  "Long enough to be useful, short enough that a pathological exception message
  cannot turn a queue table into a log store."
  500)

(defn describe
  "A one-line reason for a throwable.

  The `:reason` as well as the message, because they answer different
  questions. A reason can be grouped, counted and alerted on; the message is
  what a person reads once they have decided which group to look at. Recording
  only the message makes every dead letter free text, and free text is what
  nobody can build a dashboard out of."
  [t]
  (let [reason  (:reason (ex-data t))
        message (or (ex-message t) (str (class t)))]
    (-> (if reason (str (name reason) ": " message) message)
        (str/replace #"\s+" " ")
        (as-> line (subs line 0 (min (count line) max-length))))))
