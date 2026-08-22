(ns lab29.notifications.adapter.memory
  "An in-memory emailer that honours the same port.

  Note what it does *not* do: deduplicate. Doing so would make the fakes
  kinder than reality and would let a use-case test pass while the deployed
  system sends two receipts. A test double that is easier than the thing it
  doubles is a way of not finding out."
  (:require [lab29.notifications.port :as port]))

(defrecord MemoryEmailer [sent rejects]
  port/Emailer
  (provider-name [_] "memory")

  (send! [_ {:keys [to] :as message}]
    (if (contains? rejects to)
      {:outcome :rejected :because "invalid_recipient"}
      (let [reference (str "mem_" (random-uuid))]
        (swap! sent conj (assoc message :reference reference))
        {:outcome :sent :reference reference}))))

(defn emailer
  ([] (emailer #{"bounced@example.test"}))
  ([rejects] (->MemoryEmailer (atom []) rejects)))

(defn sent-messages [emailer] @(:sent emailer))
