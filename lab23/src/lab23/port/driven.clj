(ns lab23.port.driven
  "The **driven** ports: what the application asks the world for.

  Driving and driven describe a relationship to the application, not a
  technology. The application *calls* these, so they are driven. An inbound
  HTTP request arrives through a **driving** adapter and calls the application
  — same protocol, opposite side of the hexagon. A Stripe client would be a
  driven adapter over the very same HTTP.

  There is no `port/driving.clj`, and that is deliberate — but not because
  there is only one driving adapter. There are several: HTTP, the demo, and
  every test namespace. **A test is a driving adapter.**

  The asymmetry is elsewhere. A protocol buys substitutability of the thing
  *behind* a port:

    driven   one port, many implementations   memory or Postgres, swapped
    driving  one implementation, many callers HTTP, demo, tests, all calling

  On the driven side what varies is the implementation, so a protocol is the
  mechanism. On the driving side the thing behind the port is your
  application, and there is one of those; what varies is who calls it, and a
  caller needs no protocol to call a function.

  Four protocols, and every one of them names something the *core* must never
  do — read, write, look at a clock, invent a number. Twenty labs argued those
  belong at the edge one at a time; this is where they become a boundary you
  can point at.

  Note what is not here. There is no `TruckRepository`, no `save-truck`, no
  `find-by-id`. A port describes what the outside world can *do for you*, not
  a shape borrowed from the domain — an event store appends and reads streams,
  and that is the whole of its vocabulary.")

(defprotocol EventStore
  "Somewhere durable to put facts and get them back."
  (append [this stream-id expected-version command events]
    "Append `events` to `stream-id` if it is still at `expected-version`.
     Throws on conflict. Returns the events as recorded.")
  (read-stream [this stream-id]
    "One stream's history, oldest first.")
  (stream-version [this stream-id]
    "The version of the last event in a stream, or 0.")
  (read-since [this position]
    "Everything appended after `position`, across all streams."))

(defprotocol Outbox
  "Somewhere to leave a message for another module."
  (enqueue [this messages]
    "Record outgoing messages. Called inside the store's transaction.")
  (pending [this]
    "Messages not yet delivered."))

(defprotocol Clock
  "The current time, as an input rather than an ambient fact (lab 11)."
  (now [this]))

(defprotocol Ids
  "Fresh identity, as an input rather than an ambient fact (lab 4)."
  (new-id [this]))
