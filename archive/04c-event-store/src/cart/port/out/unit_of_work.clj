(ns cart.port.out.unit-of-work)

(defprotocol UnitOfWork
  (commit! [unit-of-work acceptance]
    "Atomically accepts events, projections and one command result.

     Returns {:status :ok|:idempotent|:request-misuse|:conflict} and, for
     success statuses, :result."))
