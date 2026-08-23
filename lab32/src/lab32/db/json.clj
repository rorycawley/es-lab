(ns lab32.db.json
  "JSONB in and out of next.jdbc, and the two coercions that are not optional.

  Gotcha #9. Without the write side, every insert fails with *column is of type
  jsonb but expression is of type character varying*, because JDBC has no idea
  that a string you handed it was meant as a document. Without the read side,
  every `SELECT` hands back an opaque `PGobject` and the caller writes
  `(.getValue ...)` at forty call sites.

  Lab 19 solved the same problem with a private `->jsonb`/`<-jsonb` pair in its
  store. That was right for one namespace touching one table. This lab has
  three tables in three schemas read by four components, so the read half moves
  into the protocol where next.jdbc will apply it everywhere, and the write
  half deliberately does not."
  (:require [clojure.data.json :as json]
            [next.jdbc.result-set :as rs])
  (:import (org.postgresql.util PGobject)))

(defn ->jsonb
  "Wrap a Clojure value as a `jsonb` parameter.

  Explicit, and staying explicit. next.jdbc lets you extend `SettableParameter`
  to `clojure.lang.IPersistentMap`, so that every map passed as a parameter
  silently becomes JSON -- and that is a trap. A map is also how you pass a
  composite value to a driver that understands one, and a global rule that
  turns every map into a document is impossible to opt out of at the one call
  site where you needed the other thing. Writing `->jsonb` costs six characters
  and says what is happening."
  [value]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-str value))))

(defn <-jsonb
  "Read a `jsonb` value back as Clojure data.

  `:bigdec true` is Gotcha #10 and is not a preference. Without it,
  `clojure.data.json` decodes `10000.50` to a Double, and a system that decides
  whether a movement crosses a reporting threshold now has a rounding error in
  the middle of it. `money_test.clj` asserts that no Double survives a round
  trip anywhere in this lab.

  Note the asymmetry lab 19 documented and it still holds: `:key-fn keyword`
  restores *keys* because their names are known in advance. Values cannot be
  restored, because by the time you are reading, a keyword that was written is
  indistinguishable from a string that was always a string. Nothing in this
  lab writes a keyword into a document."
  [^PGobject o]
  (when o
    (json/read-str (.getValue o) :key-fn keyword :bigdec true)))

;; ---------------------------------------------------------------------------
;; The read side, applied everywhere.
;;
;; Note the dispatch on `.getType`. `PGobject` is not the JSONB type, it is the
;; driver's box for *every* type JDBC has no mapping for -- which in this
;; schema includes `messaging.msg_status`, the enum on the outbox and inbox
;; status columns. A `read-column-by-index` that assumed JSON would try to
;; parse the string `PENDING` as a document and throw, and it would throw from
;; inside the result-set builder where the stack trace is least helpful.
;; ---------------------------------------------------------------------------

(extend-protocol rs/ReadableColumn
  PGobject
  (read-column-by-label [^PGobject o _]
    (if (#{"json" "jsonb"} (.getType o)) (<-jsonb o) (.getValue o)))
  (read-column-by-index [^PGobject o _ _]
    (if (#{"json" "jsonb"} (.getType o)) (<-jsonb o) (.getValue o))))
