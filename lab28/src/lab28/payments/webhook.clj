(ns lab28.payments.webhook
  "The complete `Provider callback` slice.

  A webhook is an integration event that arrives from somewhere you do not
  control, over a channel anyone can post to, with delivery semantics chosen by
  the sender. Every one of those clauses costs something:

  | you do not control it | the vocabulary is theirs, so translate at the door |
  | anyone can post to it | verify the signature before believing a byte      |
  | they choose delivery  | at-least-once, out of order, and forever          |

  The last one is the one that surprises people. Providers retry on any
  non-2xx, so an exception thrown here does not lose the event -- it schedules
  a copy of it. Which means the endpoint must answer 2xx for things it has
  already seen and for things it does not care about, and reserve failure for
  the cases where a retry could actually help.

  The claim is keyed by the *provider's* event id. We did not mint it and have
  nothing else to recognise a redelivery by."
  (:require [lab28.payments.announce :as announce]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn- claim!
  [tx {:keys [provider provider-event-id event-type]}]
  (jdbc/execute-one!
   tx
   ["INSERT INTO payments.webhook_inbox (provider, provider_event_id, event_type)
     VALUES (?, ?, ?)
     ON CONFLICT (provider, provider_event_id) DO NOTHING
     RETURNING provider_event_id"
    provider provider-event-id event-type]))

(def ^:private open-states
  "The states a callback may still move a payment out of.

  `pending` is in here because it is the state a callback exists to resolve:
  the synchronous answer was \"ask me later\", and this is later. `settled` and
  `declined` are not, because a payment that has reached an answer does not get
  a second one from a redelivered envelope."
  ["requested" "authorized" "pending"])

(defn- payment-by-reference [tx reference]
  (jdbc/execute-one!
   tx
   ["SELECT payment_id, order_id, order_fact_id, amount_cents, customer_email,
            correlation_id, status
       FROM payments.payment WHERE gateway_reference = ?"
    reference]
   opts))

(defn handle!
  "Apply one already-translated, already-verified provider event.

  Takes the *domain* shape produced by an adapter's anticorruption layer, never
  the provider's own. This namespace contains no vendor vocabulary at all,
  which is what makes a second provider an adapter rather than a rewrite.

  Everything below happens in one transaction: the claim that stops a
  redelivery, the state change, and the announcement that state change
  justifies. Lab 20 made that argument for a module-to-module inbox. A provider
  callback is the same shape with a less cooperative sender."
  [{:keys [datasource new-id]} {:keys [kind reference because] :as event}]
  (if (= :ignored kind)
    ;; A type we never subscribed to. Acknowledged and dropped, without a row:
    ;; recording every event a chatty provider sends is a table that grows
    ;; forever to answer no question.
    {:ignored (:event-type event)}
    (jdbc/with-transaction [tx datasource]
      (if-not (claim! tx event)
        {:duplicate (:provider-event-id event)}
        (if-let [payment (payment-by-reference tx reference)]
          (if-not (some #{(:status payment)} open-states)
            ;; Understood, and about a payment that has already reached an
            ;; answer. Distinct from never having heard of it: this one an
            ;; operator can look up, and it usually means the provider re-sent
            ;; the same news under a new event id.
            {:already-applied (select-keys payment [:payment-id :order-id :status])}
            (let [updated
                  (case kind
                    :payment/settled
                    (do (jdbc/execute-one!
                         tx
                         ["UPDATE payments.payment
                              SET status = 'settled', settled_at = now()
                            WHERE payment_id = ?"
                          (:payment-id payment)])
                        ;; The other path to the same conclusion. If the
                        ;; synchronous authorization already announced this
                        ;; payment, the unique constraint makes this a no-op.
                        (announce/succeeded! tx new-id (:order-fact-id payment) payment)
                        (assoc payment :status "settled"))

                    :payment/declined
                    (do (jdbc/execute-one!
                         tx
                         ["UPDATE payments.payment
                              SET status = 'declined', decline_reason = ?
                            WHERE payment_id = ?"
                          (or because "provider_declined") (:payment-id payment)])
                        (assoc payment :status "declined")))]
              {:accepted (select-keys updated [:payment-id :order-id :status])}))
          ;; Signed, understood, claimed -- and about a payment this module has
          ;; no row for at all. Providers will happily tell you about something
          ;; you have forgotten, or never knew.
          {:unmatched reference})))))
