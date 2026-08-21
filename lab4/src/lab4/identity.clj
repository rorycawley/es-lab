(ns lab4.identity
  "Examples of a Command, a Domain Event, and an Integration Message that each
  carry their own identity, and of where that identity comes from, for an Ice
  Cream truck."
  (:import (java.util Random UUID)))

;; ---------------------------------------------------------------------------
;; The fact needs an identity.
;;
;; Two vanilla ice creams were sold. Same type, same data, different facts.
;; Without :event/id these two maps are indistinguishable, and `distinct`
;; would silently collapse them into one.
;; ---------------------------------------------------------------------------

(def flavour-sold-vanilla
  {:event/id   #uuid "018f7a3e-0000-7000-8000-000000000001"
   :event/type :flavour-sold
   :data       {:flavour "vanilla"}})

(def flavour-sold-chocolate
  {:event/id   #uuid "018f7a3e-0000-7000-8000-000000000002"
   :event/type :flavour-sold
   :data       {:flavour "chocolate"}})

(def flavour-sold-vanilla-again
  {:event/id   #uuid "018f7a3e-0000-7000-8000-000000000003"
   :event/type :flavour-sold
   :data       {:flavour "vanilla"}})

(def events
  [flavour-sold-vanilla
   flavour-sold-chocolate
   flavour-sold-vanilla-again])

;; ---------------------------------------------------------------------------
;; The request needs one too — for the opposite reason.
;;
;; The customer taps "buy" and the connection stalls, so the till sends the
;; request again. That is ONE request delivered twice, not two requests, so
;; both carry the SAME :command/id and the truck sells one cone.
;; ---------------------------------------------------------------------------

(def buy-flavour-vanilla-command
  {:command/id   #uuid "018f7a3d-0000-7000-8000-0000000000a1"
   :command/type :buy-flavour
   :data         {:flavour "vanilla"}})

(def buy-flavour-vanilla-retry
  "The same request, sent a second time after a stalled connection."
  buy-flavour-vanilla-command)

(def buy-flavour-chocolate-command
  {:command/id   #uuid "018f7a3d-0000-7000-8000-0000000000a2"
   :command/type :buy-flavour
   :data         {:flavour "chocolate"}})

(def commands
  [buy-flavour-vanilla-command
   buy-flavour-chocolate-command])

;; ---------------------------------------------------------------------------
;; The delivery needs one as well — and it is not the fact's.
;;
;; One sale, published and then republished after a broker hiccup. That is ONE
;; fact told twice, so there are two :message/id values and one :event/id,
;; carried inside the payload where the receiving module reads it as data.
;; ---------------------------------------------------------------------------

(def flavour-sold-vanilla-message
  {:message/id   #uuid "018f7a3f-0000-7000-8000-0000000000f1"
   :message/type :flavour-sold
   :payload      {:event/id (:event/id flavour-sold-vanilla)
                  :flavour  "vanilla"}})

(def flavour-sold-vanilla-message-again
  "The same sale, delivered a second time. New envelope, same fact."
  {:message/id   #uuid "018f7a3f-0000-7000-8000-0000000000f2"
   :message/type :flavour-sold
   :payload      {:event/id (:event/id flavour-sold-vanilla)
                  :flavour  "vanilla"}})

(def flavour-sold-chocolate-message
  {:message/id   #uuid "018f7a3f-0000-7000-8000-0000000000f3"
   :message/type :flavour-sold
   :payload      {:event/id (:event/id flavour-sold-chocolate)
                  :flavour  "chocolate"}})

(def messages
  [flavour-sold-vanilla-message
   flavour-sold-vanilla-message-again
   flavour-sold-chocolate-message])

;; ---------------------------------------------------------------------------
;; Where the identity comes from.
;;
;; UUIDv4 is 122 random bits. Two events created a second apart are unrelated
;; values, so an index over them has no locality: every insert lands in a
;; random leaf page. UUIDv7 puts a 48-bit millisecond timestamp in the high
;; bits, so ids generated in time order are also in sort order, and an append
;; only ever touches the rightmost page of the index.
;; ---------------------------------------------------------------------------

(defn uuid-v4
  "A random UUID. Every bit but the version and variant markers is entropy."
  ^UUID []
  (random-uuid))

(defn uuid-v7
  "A time-ordered UUID built from `unix-millis` and bits drawn from `rng`.

  Layout (RFC 9562):
    48 bits  unix timestamp in milliseconds
     4 bits  version (7)
    12 bits  random
     2 bits  variant (0b10)
    62 bits  random

  Both the clock reading and the randomness are arguments rather than
  ambient calls, which is what makes this function testable at all."
  ^UUID [^long unix-millis ^Random rng]
  (let [msb (bit-or (bit-shift-left (bit-and unix-millis 0xFFFFFFFFFFFF) 16)
                    (bit-shift-left 0x7 12)
                    (bit-and (.nextLong rng) 0xFFF))
        lsb (bit-or Long/MIN_VALUE                        ; variant bits 0b10
                    (bit-and (.nextLong rng) 0x3FFFFFFFFFFFFFFF))]
    (UUID. msb lsb)))

;; ---------------------------------------------------------------------------
;; Generating an id is an effect. Take it as an argument.
;; ---------------------------------------------------------------------------

(defn buy-flavour
  "Build a `buy-flavour` command, taking its identity from `gen-id`.

  The caller mints this id — the till, the app, the customer's device — which
  is what lets a retry reuse it."
  [gen-id flavour]
  {:command/id   (gen-id)
   :command/type :buy-flavour
   :data         {:flavour flavour}})

(defn flavour-sold
  "Build a `flavour-sold` event, taking its identity from `gen-id`.

  `gen-id` is a no-argument function returning a UUID. In production it is
  `uuid-v4`, or a closure over a clock and an RNG for `uuid-v7`. In a test it
  is whatever makes the assertion readable, usually `(constantly some-uuid)`."
  [gen-id flavour]
  {:event/id   (gen-id)
   :event/type :flavour-sold
   :data       {:flavour flavour}})

(defn flavour-sold-message
  "Build an integration message announcing `event`, taking the envelope's own
  identity from `gen-id`.

  The message id is minted here, at the moment of sending — a second delivery
  of the same event gets a new one. The event id travels inside the payload
  unchanged, which is what lets a consumer deduplicate on the fact."
  [gen-id event]
  {:message/id   (gen-id)
   :message/type (:event/type event)
   :payload      (assoc (:data event) :event/id (:event/id event))})

(defn uuid-v7-generator
  "A `gen-id` function producing time-ordered UUIDs from `clock` and `rng`.

  `clock` is a no-argument function returning unix milliseconds; the system
  clock is `#(System/currentTimeMillis)`, and a test can pass a counter."
  [clock ^Random rng]
  (fn [] (uuid-v7 (clock) rng)))
