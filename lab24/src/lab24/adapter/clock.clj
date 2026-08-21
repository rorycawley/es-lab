(ns lab24.adapter.clock
  "Time and identity as adapters.

  Lab 4 argued that minting an id is an effect and belongs in an argument.
  Lab 11 argued the same about reading a clock. Both arguments were made one
  lab at a time; here they arrive at the same place — two more adapters behind
  two more ports, indistinguishable in kind from the database.

  Which is the point. 'Effect' is not a synonym for 'I/O'. Anything that makes
  a function return something different for the same inputs belongs out here."
  (:require [lab24.port.driven :as driven])
  (:import (java.util UUID)))

(defrecord SystemClock []
  driven/Clock
  (now [_] (java.util.Date.)))

(defrecord FixedClock [instant]
  driven/Clock
  (now [_] instant))

;; ---------------------------------------------------------------------------
;; A clock a test can wind forward.
;;
;; Lab 21 said a clock belongs in an argument and demonstrated it with a clock
;; held still, which is enough for reproducible timestamps. Token expiry is the
;; first thing in this repository that needs the *other* half — time passing,
;; on demand, without passing.
;;
;; An access token is valid for five minutes. Testing that it stops being
;; valid can cost five minutes, or it can cost nothing, and the difference is
;; whether `now` is a port.
;; ---------------------------------------------------------------------------

(defrecord HeldClock [instant]
  driven/Clock
  (now [_] @instant))

(defrecord RandomIds []
  driven/Ids
  (new-id [_] (UUID/randomUUID)))

(defrecord CountingIds [n]
  driven/Ids
  (new-id [_]
    (UUID/fromString (format "00000000-0000-4000-8000-%012d" (swap! n inc)))))

(defn system-clock [] (->SystemClock))
(defn fixed-clock
  "A clock a test can hold still — the reason `now` is a port at all."
  [instant]
  (->FixedClock instant))

(defn held-clock
  "A clock that starts now and moves only when told."
  ([] (held-clock (java.util.Date.)))
  ([^java.util.Date start] (->HeldClock (atom start))))

(defn advance!
  "Move a held clock forward by `seconds`, and return the new instant."
  [held-clock seconds]
  (swap! (:instant held-clock)
         (fn [^java.util.Date instant]
           (java.util.Date. (+ (.getTime instant) (* 1000 (long seconds)))))))

(defn random-ids [] (->RandomIds))
(defn counting-ids
  "Predictable ids, so a demo prints the same thing twice."
  []
  (->CountingIds (atom 0)))
