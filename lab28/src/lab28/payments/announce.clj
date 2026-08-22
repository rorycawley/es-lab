(ns lab28.payments.announce
  "Announcing that a payment succeeded, from either of the two paths that can
  discover it.

  The synchronous authorization discovers it most of the time. A provider
  callback discovers it when the authorization came back `pending` -- a card
  needing 3-D Secure, a transaction held for review -- and the two can race.

  Neither path knows about the other, and neither has to: `payments.outbox`
  carries `UNIQUE (payment_id)`, so the first one through wins and the second
  writes nothing. That constraint is the coordination, which is why it is in
  the schema with a comment rather than in a lock somewhere."
  (:require [lab28.payments.contract :as contract]
            [lab28.platform.telemetry :as telemetry]
            [next.jdbc :as jdbc]))

(defn succeeded!
  "Enqueue the public message, unless this payment already has one.

  Must be called inside the caller's transaction -- the announcement and the
  state change that justified it commit together, or neither does."
  [tx new-id causation-id {:keys [payment-id order-id amount-cents customer-email
                                  correlation-id]}]
  (jdbc/execute-one!
   tx
   ["INSERT INTO payments.outbox
       (message_id, message_type, fact_id, causation_id, correlation_id,
        traceparent, payment_id, order_id, amount_cents, customer_email, published)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE)
     ON CONFLICT (payment_id) DO NOTHING
     RETURNING fact_id"
    (new-id)
    (str (namespace contract/payment-succeeded-type) "/"
         (name contract/payment-succeeded-type))
    ;; A new fact id, because this is a new fact. Reusing the incoming one
    ;; would make two different things -- an order being placed and a payment
    ;; succeeding -- share an identity, and the first consumer to key an inbox
    ;; on it would silently swallow the other.
    (new-id)
    ;; Causation is what caused this: the fact we were reacting to.
    causation-id
    correlation-id
    (get (telemetry/trace-headers) "traceparent")
    payment-id order-id amount-cents customer-email]))
