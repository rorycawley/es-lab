(ns cart.slice.add-product-item.handler
  "Imperative shell for the add-product-item task."
  (:require [cart.application.cart-result :as cart-result]
            [cart.application.command-pipeline :as pipeline]
            [cart.application.input :as input]
            [cart.domain.aggregate :as aggregate]
            [cart.domain.project :as project]
            [cart.observation :as observation]
            [cart.port.out.event-store :as event-store]
            [cart.port.out.idempotency-store :as idempotency-store]
            [cart.port.out.unit-of-work :as unit-of-work]
            [cart.slice.add-product-item.port :as port]
            [clojure.set :as set])
  (:import [java.time Instant]
           [java.util UUID]))

(def ^:private first-keys #{:request-id :product-item})
(def ^:private existing-keys
  #{:request-id :cart-id :cart-observation :product-item})
(def ^:private product-keys #{:product-id :quantity})

(defn- field-error [field code]
  {:field field :code code})

(defn- invalid [code field-errors]
  (pipeline/outcome :invalid {:code code :field-errors (vec field-errors)}))

(defn- normalize-input [request]
  (let [request       (if (map? request) request {})
        supplied      (set (keys request))
        first?        (and (not (contains? supplied :cart-id))
                           (not (contains? supplied :cart-observation)))
        expected      (if first? first-keys existing-keys)
        product       (:product-item request)
        product-map?  (map? product)
        request-id    (input/parse-uuid (:request-id request))
        product-id    (when product-map? (input/parse-uuid (:product-id product)))
        quantity      (when product-map? (:quantity product))
        cart-id       (when-not first? (input/parse-uuid (:cart-id request)))
        shape-errors  (concat
                       (for [field (sort (set/difference supplied expected))]
                         (field-error (name field) :unknown-field))
                       (for [field (sort (set/difference expected supplied))]
                         (field-error (name field) :required))
                       (when (and product-map?
                                  (not= product-keys (set (keys product))))
                         [(field-error "product-item" :invalid-shape)]))
        value-errors  (concat
                       (when-not request-id
                         [(field-error "request-id" :invalid-uuid)])
                       (when-not product-map?
                         [(field-error "product-item" :invalid-object)])
                       (when (and product-map? (nil? product-id))
                         [(field-error "product-item.product-id" :invalid-uuid)])
                       (when-not (and (integer? quantity)
                                      (<= 1 quantity aggregate/max-product-quantity))
                         [(field-error "product-item.quantity" :invalid-quantity)])
                       (when (and (not first?) (nil? cart-id))
                         [(field-error "cart-id" :invalid-cart)]))
        errors        (vec (concat shape-errors value-errors))]
    (if (seq errors)
      (invalid (if (and (not first?) (nil? cart-id))
                 :invalid-cart
                 :invalid-request)
               errors)
      (if first?
        (pipeline/proceed
         {:command/observation-required? false
          :mode :first
          :request-id request-id
          :product-id product-id
          :quantity (long quantity)
          :canonical-command {:command-type "add-product-item"
                              :mode "first"
                              :product-id (str product-id)
                              :quantity (long quantity)}})
        (pipeline/proceed
         {:command/observation-required? true
          :mode :existing
          :request-id request-id
          :cart-id cart-id
          :product-id product-id
          :quantity (long quantity)
          :cart-observation (:cart-observation request)})))))

(defn- public-outcome [{:keys [outcome data]}]
  (merge {:outcome outcome} data))

(defn- enrich-event [uuid-fn clock revision proposed]
  (assoc proposed
         :event/id (uuid-fn)
         :event/revision revision
         :event/accepted-at (clock)
         :event/metadata {}))

(defn- commit-outcome [commit-result]
  (case (:status commit-result)
    (:ok :idempotent)
    (pipeline/outcome :success {:result (:result commit-result)})

    :request-misuse
    (invalid :request-id-reused
             [(field-error "request-id" :already-used-for-different-command)])

    :conflict
    (pipeline/outcome :conflict
                      {:code :cart-changed
                       :next-action :view-cart-before-retrying})

    (throw (ex-info "Unknown unit-of-work result" commit-result))))

(defn- acceptance [dependencies context cart-id stream]
  (let [{:keys [uuid-fn clock key-ring unit-of-work]} dependencies
        state    (aggregate/fold (:events stream))
        decision (aggregate/decide-add-product-item
                  state
                  {:cart-id cart-id
                   :product-id (:product-id context)
                   :quantity (:quantity context)})]
    (if-let [rejection (:rejection decision)]
      (pipeline/outcome :rejected rejection)
      (let [revision       (inc (:revision stream))
            events         (mapv #(enrich-event uuid-fn clock revision %)
                                 (:events decision))
            current-view   (project/cart-view-from-state state)
            next-view      (reduce project/cart-view current-view events)
            history        (mapv project/history-entry events)
            result          (cart-result/cart-result key-ring next-view)
            committed       (unit-of-work/commit!
                             unit-of-work
                             {:request-id (:request-id context)
                              :canonical-command (:canonical-command context)
                              :stream-key cart-id
                              :expected (if (= :first (:mode context))
                                          :absent
                                          (:revision stream))
                              :events events
                              :cart-view next-view
                              :history-entries history
                              :successful-result result})]
        (commit-outcome committed)))))

(defn- validate-existing [dependencies context]
  (let [represented (observation/verify (:key-ring dependencies)
                                        (:cart-observation context))]
    (cond
      (:error represented)
      (invalid :invalid-cart-observation
               [(field-error "cart-observation" :invalid)])

      (not= (:cart-id context) (get-in represented [:ok :cart-id]))
      (invalid :invalid-cart-observation
               [(field-error "cart-observation" :wrong-cart)])

      :else
      (let [stream (event-store/read-stream (:event-store dependencies)
                                            (:cart-id context))]
        (if-not (:exists? stream)
          (invalid :invalid-cart [(field-error "cart-id" :invalid-cart)])
          (let [represented (:ok represented)]
            (pipeline/proceed
             (assoc context
                    :represented-observation represented
                    :stream stream
                    :canonical-command
                    {:command-type "add-product-item"
                     :mode "existing"
                     :cart-id (str (:cart-id context))
                     :expected-revision (:revision represented)
                     :product-id (str (:product-id context))
                     :quantity (:quantity context)}))))))))

(defn- validate-step [dependencies request]
  (let [normalized (normalize-input request)]
    (if (not= :proceed (:pipeline/status normalized))
      normalized
      (let [context (:pipeline/context normalized)]
        (if (= :existing (:mode context))
          (validate-existing dependencies context)
          normalized)))))

(defn- replay-step [dependencies context]
  (if-let [accepted (idempotency-store/find-command-result
                     (:idempotency-store dependencies)
                     (:request-id context))]
    (if (= (:canonical-command context) (:canonical-command accepted))
      (pipeline/outcome :success {:result (:result accepted)})
      (invalid :request-id-reused
               [(field-error "request-id" :already-used-for-different-command)]))
    (pipeline/proceed context)))

(defn- observation-step [context]
  (if (= (get-in context [:represented-observation :revision])
         (get-in context [:stream :revision]))
    (pipeline/proceed context)
    (pipeline/outcome :conflict
                      {:code :cart-changed
                       :next-action :view-cart-before-retrying})))

(defn- first-add-step [dependencies context]
  (loop [attempt 0]
    (when (= 10 attempt)
      (throw (ex-info "Could not allocate a unique cart identifier"
                      {:attempts attempt})))
    (let [cart-id ((:uuid-fn dependencies))
          result  (acceptance dependencies
                              context
                              cart-id
                              {:exists? false :revision 0 :events []})]
      (if (= :conflict (:outcome result))
        (recur (inc attempt))
        result))))

(defn- business-step [dependencies context]
  (if (= :first (:mode context))
    (first-add-step dependencies context)
    (acceptance dependencies
                context
                (:cart-id context)
                (:stream context))))

(defrecord Handler [event-store idempotency-store unit-of-work
                    key-ring uuid-fn clock]
  port/AddProductItem
  (add-product-item [this request]
    (-> (pipeline/evaluate
         {:validate-input #(validate-step this %)
          :resolve-replay #(replay-step this %)
          :check-observation observation-step
          :apply-business-rules #(business-step this %)}
         request)
        public-outcome)))

(defn new-handler
  [{:keys [event-store idempotency-store unit-of-work
           key-ring uuid-fn clock]
    :or {uuid-fn #(UUID/randomUUID)
         clock #(Instant/now)}}]
  (doseq [[label dependency]
          [[:event-store event-store]
           [:idempotency-store idempotency-store]
           [:unit-of-work unit-of-work]
           [:uuid-fn uuid-fn]
           [:clock clock]]]
    (when-not dependency
      (throw (ex-info "Missing add-product-item dependency" {:dependency label}))))
  (observation/validate-key-ring! key-ring)
  (->Handler event-store idempotency-store unit-of-work
             key-ring uuid-fn clock))
