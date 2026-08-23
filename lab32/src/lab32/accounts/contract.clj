(ns lab32.accounts.contract
  "What Accounts promises, as data.

  Lab 29's rule and it is load bearing here: `router.clj` folds the routing
  table out of these declarations, so this is the thing that is true rather
  than a description of something else that is true. A module that does not
  declare a contract cannot be routed to.

  This namespace is also the *only* thing under `src/lab32/accounts/` that
  another module is allowed to require. `architecture_test.clj` enforces that
  by scanning `ns` forms, which is what the build spec asks for in §10."
  (:require [clojure.set :as set]))

(def contract
  {:module           :accounts
   :schema           "accounts"

   ;; One integration event, and three domain events. That gap is lab 3's
   ;; whole point and it is easy to lose: `:accounts/account-opened` is a fact
   ;; Accounts records about itself and nobody outside has any business
   ;; reacting to. Publishing every domain event because it is there is how a
   ;; module's internal model becomes everybody else's compile-time dependency.
   :publishes-events #{:accounts/transaction-recorded}
   :consumes-events  #{}
   :provides-queries #{:accounts/balance :accounts/history}})

(def domain-events
  "The facts Accounts records internally. Not a contract with anyone -- listed
  here so `architecture_test.clj` can assert that the published set is a proper
  subset of it, and stays one."
  #{:accounts/account-opened
    :accounts/money-deposited
    :accounts/money-withdrawn})

(defn published-are-not-domain-events?
  "Is every published event type distinct from the internal ones?

  Deliberately not a style check. If an internal event type were also
  published, renaming a field inside the aggregate would silently change a
  message other modules parse."
  []
  (empty? (set/intersection (:publishes-events contract) domain-events)))
