(ns lab23.adapter.clock
  "Time and identity as adapters.

  Lab 4 argued that minting an id is an effect and belongs in an argument.
  Lab 11 argued the same about reading a clock. Both arguments were made one
  lab at a time; here they arrive at the same place — two more adapters behind
  two more ports, indistinguishable in kind from the database.

  Which is the point. 'Effect' is not a synonym for 'I/O'. Anything that makes
  a function return something different for the same inputs belongs out here."
  (:require [lab23.port.driven :as driven])
  (:import (java.util UUID)))

(defrecord SystemClock []
  driven/Clock
  (now [_] (java.util.Date.)))

(defrecord FixedClock [instant]
  driven/Clock
  (now [_] instant))

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

(defn random-ids [] (->RandomIds))
(defn counting-ids
  "Predictable ids, so a demo prints the same thing twice."
  []
  (->CountingIds (atom 0)))
