(ns cart.domain.aggregate
  "Pure event-sourced cart state and decisions."
  (:import [java.lang Math]
           [java.util UUID]))

(def max-product-quantity 1000)

(defn missing-cart []
  {:cart/existence :missing
   :cart/revision 0})

(defn- require-uuid [label value]
  (when-not (instance? UUID value)
    (throw (ex-info "Corrupt cart event identifier"
                    {:field label :value value})))
  value)

(defn- require-quantity [value]
  (when-not (and (integer? value) (<= 1 value max-product-quantity))
    (throw (ex-info "Corrupt cart event quantity" {:quantity value})))
  (long value))

(defn evolve
  "Applies one validated domain event to state. Unknown or corrupt events fail."
  [state event]
  (case [(:event/type event) (:event/version event)]
    [:product-item-added 1]
    (let [{:keys [cart-id product-id quantity]} (:event/data event)
          cart-id    (require-uuid :cart-id cart-id)
          product-id (require-uuid :product-id product-id)
          quantity   (require-quantity quantity)
          missing?   (= :missing (:cart/existence state))]
      (when (and (not missing?) (not= cart-id (:cart/id state)))
        (throw (ex-info "Cart event belongs to another cart"
                        {:expected (:cart/id state) :actual cart-id})))
      (when (and missing? (not= 1 (:event/revision event)))
        (throw (ex-info "First cart event must have revision 1"
                        {:revision (:event/revision event)})))
      (when (and (not missing?)
                 (not= (inc (:cart/revision state))
                       (:event/revision event)))
        (throw (ex-info "Cart event revisions are not contiguous"
                        {:expected (inc (:cart/revision state))
                         :actual (:event/revision event)})))
      (let [held (get-in state [:cart/items product-id] 0)
            next (Math/addExact (long held) quantity)]
        (when (> next max-product-quantity)
          (throw (ex-info "Accepted cart history exceeds the quantity limit"
                          {:product-id product-id :quantity next})))
        {:cart/existence :present
         :cart/id cart-id
         :cart/status (if missing? :open (:cart/status state))
         :cart/items (assoc (if missing? {} (:cart/items state)) product-id next)
         :cart/revision (:event/revision event)}))

    (throw (ex-info "Unknown cart event"
                    {:event/type (:event/type event)
                     :event/version (:event/version event)}))))

(defn fold
  "Reconstructs cart state from ordered events."
  [events]
  (reduce evolve (missing-cart) events))

(defn decide-add-product-item
  "Decides an addition. It returns proposed event data or a business rejection."
  [state {:keys [cart-id product-id quantity]}]
  (let [first-add? (= :missing (:cart/existence state))]
    (cond
      (and (not first-add?) (= :closed (:cart/status state)))
      {:rejection {:code :cart-closed}}

      (and (not first-add?) (not= cart-id (:cart/id state)))
      (throw (ex-info "Command cart does not match folded state"
                      {:command-cart cart-id :state-cart (:cart/id state)}))

      :else
      (let [held (long (get-in state [:cart/items product-id] 0))
            next (try
                   (Math/addExact held (long quantity))
                   (catch ArithmeticException _ Long/MAX_VALUE))]
        (if (> next max-product-quantity)
          {:rejection {:code :product-quantity-limit-exceeded}}
          {:events [{:event/type :product-item-added
                     :event/version 1
                     :event/data {:cart-id cart-id
                                  :product-id product-id
                                  :quantity (long quantity)}}]})))))
