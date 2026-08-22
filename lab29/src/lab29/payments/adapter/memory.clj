(ns lab29.payments.adapter.memory
  "An in-memory gateway that honours the same port.

  It exists for two reasons. Use-case tests should not need a socket, which is
  lab 21's testing strategy. And a port with one implementation is a guess: the
  second one is what proves the abstraction is about taking money rather than
  about Stripe. `gateway_contract_test.clj` runs one suite against both.

  It implements the idempotency clause honestly, by remembering payment ids --
  which is exactly what the real provider does on the other side of the wire.

  It also produces all three outcomes the port defines, including `:pending`.
  A double that can only succeed or fail models a port narrower than the real
  one, and every test written against it would pass while the deployed system
  met a card in 3-D Secure and did not know what to do."
  (:require [lab29.payments.port :as port]))

(defrecord MemoryGateway [charges declines pendings]
  port/PaymentGateway
  (provider-name [_] "memory")

  (authorize! [_ {:keys [payment-id instrument]}]
    (if-let [existing (get @charges payment-id)]
      existing
      (let [outcome (cond
                      (contains? declines instrument)
                      {:outcome :declined :because "card_declined"}

                      (contains? pendings instrument)
                      {:outcome :pending :reference (str "mem_" (random-uuid))}

                      :else
                      {:outcome :authorized :reference (str "mem_" (random-uuid))})]
        (swap! charges assoc payment-id outcome)
        outcome))))

(defn gateway
  ([] (gateway #{"pm_card_chargeDeclined"} #{"pm_card_authenticationRequired"}))
  ([declines] (gateway declines #{"pm_card_authenticationRequired"}))
  ([declines pendings] (->MemoryGateway (atom {}) declines pendings)))

(defn charge-count [gateway] (count @(:charges gateway)))
