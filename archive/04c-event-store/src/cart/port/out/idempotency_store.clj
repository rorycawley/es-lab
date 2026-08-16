(ns cart.port.out.idempotency-store)

(defprotocol IdempotencyStore
  (find-command-result [store request-id]
    "Returns the accepted canonical command and original result, or nil."))
