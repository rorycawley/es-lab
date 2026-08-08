(ns cart.port.out.event-store)

(defprotocol EventStore
  (read-stream [store stream-key]
    "Returns {:exists? boolean :revision long :events [...]}"))
