(ns lab4.identity
  "Examples of a Command, a Domain Event, and an Integration Message that each
  carry their own identifier, and of where that identifier comes from, for an
  Ice Cream truck. Identity is the logical thing being referred to; a UUID is
  the stable value used to refer to it."
  (:import (java.util Random UUID)))

;; ---------------------------------------------------------------------------
;; The fact needs an identifier.
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
;; The request needs an identifier too — for the opposite reason.
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
;; The message envelope needs one as well — and it is not the fact's.
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
  "The same sale, published in a new envelope. New message, same fact."
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
;; Where the identifier comes from.
;;
;; UUIDv4 is 122 random bits. Two events created a second apart are unrelated
;; values, so an index over them has no locality: successive inserts scatter
;; across unrelated leaf pages. UUIDv7 puts a 48-bit millisecond timestamp in
;; the high bits, so values from increasing milliseconds sort together and
;; have much better locality. Random values within one millisecond and clocks
;; on separate machines do not create a globally monotonic sequence.
;; ---------------------------------------------------------------------------

(defn uuid-v4
  "A random UUID. Every bit but the version and variant markers is entropy."
  ^UUID []
  (random-uuid))

(defn uuid-v7
  "A timestamp-prefixed UUID built from `unix-millis` and bits drawn from `rng`.

  Layout (RFC 9562):
    48 bits  unix timestamp in milliseconds
     4 bits  version (7)
    12 bits  random
     2 bits  variant (0b10)
    62 bits  random

  Both the clock reading and the randomness are arguments rather than
  ambient calls, which is what makes this function testable at all. This small
  implementation uses random bits within a millisecond and therefore does not
  promise monotonic generation order for equal timestamps."
  ^UUID [^long unix-millis ^Random rng]
  (when-not (<= 0 unix-millis 0xFFFFFFFFFFFF)
    (throw (ex-info "UUIDv7 timestamp must fit in 48 unsigned bits"
                    {:unix-millis unix-millis})))
  (let [msb (bit-or (bit-shift-left (bit-and unix-millis 0xFFFFFFFFFFFF) 16)
                    (bit-shift-left 0x7 12)
                    (bit-and (.nextLong rng) 0xFFF))
        lsb (bit-or Long/MIN_VALUE                        ; variant bits 0b10
                    (bit-and (.nextLong rng) 0x3FFFFFFFFFFFFFFF))]
    (UUID. msb lsb)))

;; ---------------------------------------------------------------------------
;; Allocating an identifier is an effect. Take it as an argument, then reject
;; invalid ids before constructing a complete envelope.
;; ---------------------------------------------------------------------------

(defn- require-uuid
  [kind candidate]
  (when-not (uuid? candidate)
    (throw (ex-info (str (name kind) " id must be a UUID")
                    {:id/kind kind :id/value candidate})))
  candidate)

(defn buy-flavour
  "Build a `buy-flavour` command, taking its identifier from `gen-id`.

  The caller mints this id — the till, the app, the customer's device — which
  is what lets a retry reuse it."
  [gen-id flavour]
  {:command/id   (require-uuid :command (gen-id))
   :command/type :buy-flavour
   :data         {:flavour flavour}})

(defn flavour-sold
  "Build a `flavour-sold` event, taking its identifier from `gen-id`.

  `gen-id` is a no-argument function returning a UUID. In production it is
  `uuid-v4`, or a closure over a clock and an RNG for `uuid-v7`. In a test it
  is whatever makes the assertion readable, usually `(constantly some-uuid)`."
  [gen-id flavour]
  {:event/id   (require-uuid :event (gen-id))
   :event/type :flavour-sold
   :data       {:flavour flavour}})

(defn flavour-sold-message
  "Build an integration message announcing `event`, taking the envelope's own
  identifier from `gen-id`.

  The message id is minted when a new envelope is created — republishing the
  same event creates another id, while broker redelivery of an existing
  envelope retains it. The event id travels inside the payload unchanged,
  which is what lets a consumer deduplicate on the fact."
  [gen-id event]
  (let [event-id   (require-uuid :event (:event/id event))
        message-id (require-uuid :message (gen-id))]
    {:message/id   message-id
     :message/type (:event/type event)
     :payload      (assoc (:data event) :event/id event-id)}))

(defn uuid-v7-generator
  "A `gen-id` function producing timestamp-prefixed UUIDs from `clock` and `rng`.

  `clock` is a no-argument function returning unix milliseconds; the system
  clock is `#(System/currentTimeMillis)`, and a test can pass a counter. Use a
  cryptographically strong `Random` implementation such as `SecureRandom` in
  production."
  [clock ^Random rng]
  (fn [] (uuid-v7 (clock) rng)))
