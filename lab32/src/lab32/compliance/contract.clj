(ns lab32.compliance.contract
  "What Compliance promises, as data.

  Note that it publishes nothing. A read-side module that consumes events and
  answers questions is a perfectly good module, and giving it an outbox it
  never writes to -- because the shape looked incomplete -- would be building a
  queue for messages that do not exist.")

(def contract
  {:module           :compliance
   :schema           "compliance"
   :publishes-events #{}
   :consumes-events  #{:accounts/transaction-recorded}
   :provides-queries #{:compliance/flagged-transactions}})
