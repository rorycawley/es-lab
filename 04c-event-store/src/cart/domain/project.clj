(ns cart.domain.project
  "Pure synchronous projectors for cart query models."
  (:require [cart.domain.aggregate :as aggregate]))

(defn cart-view-from-state
  "Creates the current projection value from rehydrated aggregate state."
  [state]
  (when (= :present (:cart/existence state))
    {:cart-id (:cart/id state)
     :revision (:cart/revision state)
     :status (:cart/status state)
     :items (:cart/items state)}))

(defn cart-view
  "Projects the complete cart view after one accepted event."
  [current event]
  (-> (aggregate/evolve
       (if current
         {:cart/existence :present
          :cart/id (:cart-id current)
          :cart/status (:status current)
          :cart/items (:items current)
          :cart/revision (:revision current)}
         (aggregate/missing-cart))
       event)
      cart-view-from-state))

(defn history-entry
  "Projects one public history row from one accepted domain event."
  [event]
  (case [(:event/type event) (:event/version event)]
    [:product-item-added 1]
    {:cart-id (get-in event [:event/data :cart-id])
     :revision (:event/revision event)
     :change-type :product-item-added
     :accepted-at (:event/accepted-at event)
     :business-data (select-keys (:event/data event) [:product-id :quantity])}

    (throw (ex-info "No projector for cart event"
                    {:event/type (:event/type event)
                     :event/version (:event/version event)}))))
