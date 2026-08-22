(ns lab29.payments.charge-order
  "The complete `Charge order` slice: accept a request, take money.

  This is the slice the whole lab is arranged around, because it is where a
  local transaction and a remote effect meet and cannot be made into one thing.

  New in lab 29: this is a **command handler**, not an event subscriber. Lab
  28 charged an order by subscribing to the fact that one had been placed,
  which made *take this money* look like news rather than a request and left
  the cardinality unstated -- nothing would have stopped a second module
  subscribing and charging the card twice.

  ## Three steps, and the gap between them is the design

      1. CLAIM        one transaction   inbox row + payment row (`requested`)
      -- crash here means step 2 has not happened --
      2. AUTHORIZE    no transaction    the gateway, keyed by our payment id
      -- crash here means the money may have moved and we do not know --
      3. RECORD       one transaction   payment status + outbox message

  Lab 20 established that a local effect can share a transaction with an inbox
  claim, and warned in the same breath that this is *not* a guarantee for
  payments. Here is the reason, in the second gap: nothing we write locally can
  make a remote charge atomic with it.

  What makes the gap survivable is that step 1 writes the payment id *before*
  step 2 uses it. A retry re-reads a `requested` payment, calls the gateway
  again with the same idempotency key, and the gateway -- which does know
  whether the money moved -- answers the same way it did the first time."
  (:require [lab29.payments.announce :as announce]
            [lab29.payments.contract :as contract]
            [lab29.payments.port :as port]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def Request
  [:map {:closed true}
   [:headers [:map-of :string :string]]
   [:message contract/ChargeOrder]])

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn- claim!
  "Step 1. The inbox row and the payment row commit together or not at all,
  and the payment id that comes back is the authoritative one.

  `DO UPDATE ... RETURNING` rather than `DO NOTHING`, and the difference is a
  double charge. Two concurrent deliveries of the same order each generate a
  candidate payment id, only one row can exist, and both must leave here
  holding the id that *won*. Using the one you generated would hand the gateway
  two different idempotency keys for one order, and the gateway would correctly
  charge twice."
  [datasource {:keys [fact-id order-id total-cents customer-email payment-method
                      correlation-id message-id payment-id]}]
  (jdbc/with-transaction [tx datasource]
    (jdbc/execute-one!
     tx
     ["INSERT INTO payments.inbox (fact_id, first_message_id, correlation_id)
       VALUES (?, ?, ?)
       ON CONFLICT (fact_id) DO NOTHING"
      fact-id message-id correlation-id])
    (jdbc/execute-one!
     tx
     ["INSERT INTO payments.payment
         (payment_id, order_id, order_fact_id, amount_cents, currency, status,
          correlation_id, customer_email, payment_method)
       VALUES (?, ?, ?, ?, 'eur', 'requested', ?, ?, ?)
       ON CONFLICT (order_id) DO UPDATE SET order_id = EXCLUDED.order_id
       RETURNING payment_id, status"
      payment-id order-id fact-id total-cents correlation-id customer-email
      payment-method]
     opts)))

(defn- payment-for-order [db order-id]
  (jdbc/execute-one!
   db
   ["SELECT payment_id, order_id, amount_cents, currency, status,
            gateway_reference, decline_reason, correlation_id, customer_email
       FROM payments.payment WHERE order_id = ?"
    order-id]
   opts))

(defn- record!
  "Step 3. The outcome, and -- if the money is ours -- the message saying so."
  [datasource gateway-name {:keys [payment-id order-id fact-id new-id] :as context}
   {:keys [outcome reference because]}]
  (jdbc/with-transaction [tx datasource]
    (jdbc/execute-one!
     tx
     ["UPDATE payments.payment
          SET status = ?, gateway_reference = ?, decline_reason = ?
        WHERE payment_id = ? AND status = 'requested'"
      (name outcome) reference because payment-id])
    (when (= :authorized outcome)
      (announce/succeeded! tx new-id fact-id context))
    (assoc (payment-for-order tx order-id) :gateway gateway-name)))

(defn handle!
  [{:keys [datasource gateway new-id]} {:keys [message]}]
  (let [{:keys [correlation-id]} (:metadata message)
        {:keys [order-id order-fact-id total-cents customer-email payment-method]}
        (:data message)
        fact-id order-fact-id
        existing (payment-for-order datasource order-id)]
    (if (and existing (not= "requested" (:status existing)))
      ;; Already answered. A redelivered order-placed must not produce a second
      ;; charge, and the inbox alone would not stop one -- the same fact can
      ;; arrive in a genuinely new envelope, as lab 25 showed.
      {:duplicate (dissoc existing :customer-email)}
      (let [claimed    (claim! datasource {:fact-id fact-id
                                           :message-id (:message/id message)
                                           :order-id order-id
                                           :total-cents total-cents
                                           :customer-email customer-email
                                           :payment-method payment-method
                                           :correlation-id correlation-id
                                           :payment-id (or (:payment-id existing) (new-id))})
            payment-id (:payment-id claimed)
            context    {:fact-id fact-id
                        :order-id order-id
                        :amount-cents total-cents
                        :customer-email customer-email
                        :correlation-id correlation-id
                        :payment-id payment-id
                        :new-id new-id}
            ;; Outside every transaction. Deliberately.
            answer     (port/authorize!
                        gateway
                        {:payment-id payment-id
                         :amount-cents total-cents
                         :currency "eur"
                           ;; Straight through from checkout. This slice has
                         ;; never seen the inside of it.
                         :instrument payment-method
                         :description (str "order " order-id)})
            row        (record! datasource (port/provider-name gateway) context answer)]
        (case (:outcome answer)
          :authorized {:accepted (dissoc row :customer-email)}
          ;; Not an answer yet. A card in 3-D Secure or a transaction held for
          ;; review is neither taken nor refused, and calling it either would
          ;; be a lie somebody acts on -- a refund for money that was never
          ;; captured, or a receipt for money that never arrives. The callback
          ;; resolves it.
          :pending    {:pending (dissoc row :customer-email)}
          {:rejected :payment-declined
           :because  (:because answer)
           :payment  (dissoc row :customer-email)})))))
