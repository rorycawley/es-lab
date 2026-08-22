(ns lab29.payments.announce
  "Announcing that a payment succeeded, from either of the two paths that can
  discover it.

  The synchronous authorization discovers it most of the time. A provider
  callback discovers it when the authorization came back `pending` -- a card
  needing 3-D Secure, a transaction held for review -- and the two can race.

  Neither path knows about the other, and neither has to: both derive the same
  message id from the payment, so the outbox's own uniqueness makes the second
  one a no-op. The coordination is an identifier rather than a lock."
  (:require [lab29.payments.contract :as contract]
            [lab29.payments.outbox :as outbox]))

(defn- announcement-id
  "A message id derived from the payment, so the second path writes nothing.

  Both routes to success -- the synchronous authorization and a callback
  settling something pending -- announce by calling this function, and neither
  checks whether the other already did. Deriving the message id from the
  payment makes them compute the same id, and `ON CONFLICT (message_id) DO
  NOTHING` in the outbox does the rest.

  A deterministic id is a cheaper coordination than a column and a unique
  index, and it survives the outbox not knowing what a payment is."
  [payment-id]
  (java.util.UUID/nameUUIDFromBytes
   (.getBytes (str "payments/payment-succeeded:" payment-id) "UTF-8")))

(defn succeeded!
  "Enqueue the public message, unless this payment already has one.

  Must be called inside the caller's transaction -- the announcement and the
  state change that justified it commit together, or neither does."
  [tx new-id causation-id {:keys [payment-id order-id amount-cents customer-email
                                  correlation-id]}]
  (outbox/enqueue-once! tx (contract/payment-succeeded
                            (announcement-id payment-id) (new-id)
                            causation-id correlation-id
                            payment-id order-id amount-cents customer-email)))
