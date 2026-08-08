(ns cart.port.event-store
  "Driven port. cart.app depends on this; adapters implement it. The dependency
   arrow points inward — adapters depend on the port, never the reverse.")

(defprotocol EventStore
  (read-stream [this stream-id]
    "=> {:events [...] :version 7 :exists? true}

     SPEC R4.1: the version comes from the store, never from (count events).
     A count is wrong for partial reads and snapshot folds, and the failure is
     a silent lost update.")

  (append-to-stream [this stream-id events expected-version]
    "expected-version: a long, :stream-does-not-exist, or :any (SPEC R4.4)

     => [:ok       {:version 9 :created-new-stream? false}]
      | [:conflict {:expected 7 :current 9}]

     SPEC R4.6: a conflict is data. The shell decides whether that becomes a
     retry or an HTTP 409."))
