(ns lab26.vertical-slice-test
  "Use-case behaviour through public module APIs and real Postgres.

  These slices deliberately own their SQL directly, so there is no repository
  port to fake. The tests remain coupled to business contracts, not SQL call
  choreography, but have integration-test cost and scope."
  (:require [clojure.test :refer [deftest is testing]]
            [lab26.catalog.api :as catalog]
            [lab26.catalog.contract :as catalog-contract]
            [lab26.fixture :as fixture]
            [lab26.ordering.api :as ordering]
            [lab26.system :as system]))

(def vanilla #uuid "0f1c2b3a-0000-4000-8000-000000000025")
(def order-1 #uuid "0f1c2b3a-0000-4000-8000-000000000101")
(def order-2 #uuid "0f1c2b3a-0000-4000-8000-000000000102")

(defn- change-vanilla! [catalog price-cents]
  (catalog/change-price! catalog {:command-id (random-uuid)
                                  :correlation-id (random-uuid)
                                  :product-id vanilla
                                  :product-name "vanilla"
                                  :price-cents price-cents}))

(defn- place-order-request [order-id quantity]
  {:order-id order-id
   :correlation-id (random-uuid)
   :product-id vanilla
   :quantity quantity
   :customer-email "ada@example.com"})

(deftest a-whole-slice-is-tested-through-its-module-api-test
  (fixture/with-system
    (fn [{:keys [catalog]}]
      (is (= {:product-id vanilla
              :product-name "vanilla"
              :current-price-cents 300}
             (dissoc (:accepted (change-vanilla! catalog 300)) :fact-id)))
      (is (= {:product-id vanilla
              :product-name "vanilla"
              :current-price-cents 300}
             (:found (catalog/get-product catalog {:product-id vanilla})))))))

(deftest module-owned-data-is-copied-only-through-a-contract-test
  (fixture/with-system
    (fn [{:keys [catalog ordering] :as app}]
      (change-vanilla! catalog 300)

      (testing "Ordering cannot place an order before it receives Catalog's message"
        (is (= :price-unavailable
               (:rejected (ordering/place-order! ordering
                                                 (place-order-request order-1 2))))))

      (testing "the outbox crosses the boundary"
        (is (= 1 (count (system/relay-catalog! app))))
        (is (= 600
               (get-in (ordering/place-order! ordering
                                              (place-order-request order-1 2))
                       [:accepted :total-cents])))))))

(deftest duplicated-data-has-different-meaning-test
  (fixture/with-system
    (fn [{:keys [catalog ordering] :as app}]
      (change-vanilla! catalog 300)
      (system/relay-catalog! app)
      (ordering/place-order! ordering (place-order-request order-1 1))

      (change-vanilla! catalog 450)
      (system/relay-catalog! app)
      (ordering/place-order! ordering (place-order-request order-2 1))

      (is (= 450 (get-in (catalog/get-product catalog {:product-id vanilla})
                         [:found :current-price-cents]))
          "Catalog owns today's price")
      (is (= 300 (get-in (ordering/get-order ordering {:order-id order-1})
                         [:found :unit-price-cents]))
          "the first order owns the price agreed then")
      (is (= 450 (get-in (ordering/get-order ordering {:order-id order-2})
                         [:found :unit-price-cents]))))))

(deftest the-inbox-makes-contract-republication-idempotent-test
  (fixture/with-system
    (fn [{:keys [catalog ordering] :as app}]
      (change-vanilla! catalog 300)
      (let [{:keys [message headers]} (first (system/relay-catalog! app))]
        (is (= (get-in message [:payload :fact-id])
               (:duplicate (ordering/receive!
                            ordering
                            {:headers headers
                             :message (assoc message :message/id (random-uuid))})))
            "a newly enveloped publication of the same fact is still a duplicate")
        (is (empty? (system/relay-catalog! app))
            "the outbox has already marked this delivery")))))

(deftest validation-is-a-behaviour-around-the-slice-test
  (fixture/with-system
    (fn [{:keys [catalog]}]
      (is (= :malformed
             (:rejected (catalog/change-price! catalog
                                               {:command-id (random-uuid)
                                                :correlation-id (random-uuid)
                                                :product-id vanilla
                                                :product-name "vanilla"
                                                :price-cents 0}))))
      (is (= vanilla (:not-found (catalog/get-product catalog
                                                      {:product-id vanilla}))))
      (is (= [{:request :catalog/change-price :outcome :malformed}
              {:request :catalog/get-product :outcome :completed}]
             (catalog/audit-log catalog))))))

(deftest catalog-command-retries-return-one-atomic-outcome-test
  (fixture/with-system
    (fn [{:keys [catalog] :as app}]
      (let [command-id (random-uuid)
            correlation-id (random-uuid)
            request {:command-id command-id
                     :correlation-id correlation-id
                     :product-id vanilla
                     :product-name "vanilla"
                     :price-cents 300}
            first-result (catalog/change-price! catalog request)]
        (is (= first-result (catalog/change-price! catalog request)))
        (is (= first-result
               (catalog/change-price! catalog
                                      (assoc request :correlation-id (random-uuid))))
            "correlation traces an attempt; it is not part of command intent")
        (let [[{:keys [message]}] (system/relay-catalog! app)]
          (is (= (get-in first-result [:accepted :fact-id])
                 (get-in message [:payload :fact-id])))
          (is (= command-id (get-in message [:metadata :causation-id])))
          (is (= correlation-id (get-in message [:metadata :correlation-id])))
          (is (empty? (system/relay-catalog! app))
              "an exact retry did not create a second outbox message"))))))

(deftest catalog-command-id-reuse-with-different-intent-is-a-collision-test
  (fixture/with-system
    (fn [{:keys [catalog]}]
      (let [command-id (random-uuid)
            request {:command-id command-id :correlation-id (random-uuid)
                     :product-id vanilla :product-name "vanilla" :price-cents 300}]
        (catalog/change-price! catalog request)
        (let [failure (try
                        (catalog/change-price! catalog (assoc request :price-cents 450))
                        (catch clojure.lang.ExceptionInfo e e))]
          (is (= :command-id-collision (:reason (ex-data failure)))))))))

(deftest placing-an-order-is-idempotent-even-after-the-current-price-changes-test
  (fixture/with-system
    (fn [{:keys [catalog ordering] :as app}]
      (change-vanilla! catalog 300)
      (system/relay-catalog! app)
      (let [request (place-order-request order-1 2)
            first-result (ordering/place-order! ordering request)]
        (change-vanilla! catalog 450)
        (system/relay-catalog! app)
        (is (= first-result (ordering/place-order! ordering request))
            "the retry returns the captured price, not today's price")
        (let [failure (try
                        (ordering/place-order! ordering (assoc request :quantity 3))
                        (catch clojure.lang.ExceptionInfo e e))]
          (is (= :order-id-collision (:reason (ex-data failure)))))))))

(deftest concurrent-contract-redelivery-has-one-atomic-inbox-winner-test
  (fixture/with-system
    (fn [{:keys [ordering]}]
      (let [message-id (random-uuid)
            fact-id    (random-uuid)
            delivery   {:headers {}
                        :message (catalog-contract/price-changed
                                  message-id fact-id (random-uuid) (random-uuid)
                                  vanilla "vanilla" 300)}
            gate       (promise)
            attempts   (reduce (fn [started _]
                                 (conj started
                                       (future
                                         @gate
                                         (ordering/receive! ordering delivery))))
                               []
                               (range 8))]
        (deliver gate true)
        (let [results (mapv deref attempts)]
          (is (= 1 (count (filter :accepted results))))
          (is (= 7 (count (filter :duplicate results)))))))))

(deftest concurrent-command-retries-converge-on-the-captured-outcome-test
  (fixture/with-system
    (fn [{:keys [catalog] :as app}]
      (let [request  {:command-id (random-uuid)
                      :correlation-id (random-uuid)
                      :product-id vanilla
                      :product-name "vanilla"
                      :price-cents 300}
            gate     (promise)
            attempts (reduce (fn [started _]
                               (conj started
                                     (future
                                       @gate
                                       (catalog/change-price! catalog request))))
                             []
                             (range 8))]
        (deliver gate true)
        (let [results (mapv deref attempts)]
          (is (apply = results))
          (is (= 1 (count (system/relay-catalog! app)))))))))

(deftest product-ledger-and-outbox-roll-back-as-one-catalog-outcome-test
  (let [one-message-id   (random-uuid)
        ids              (atom [(random-uuid) one-message-id
                                (random-uuid) one-message-id
                                (random-uuid) (random-uuid)])
        next-id          (fn []
                           (let [[id & remaining] @ids]
                             (reset! ids remaining)
                             id))]
    (fixture/with-system
      {:new-id next-id}
      (fn [{:keys [catalog]}]
        (let [first-request  {:command-id (random-uuid)
                              :correlation-id (random-uuid)
                              :product-id vanilla
                              :product-name "vanilla"
                              :price-cents 300}
              second-request (assoc first-request
                                    :command-id (random-uuid)
                                    :price-cents 450)]
          (catalog/change-price! catalog first-request)
          (is (thrown? java.sql.SQLException
                       (catalog/change-price! catalog second-request)))
          (is (= 300
                 (get-in (catalog/get-product catalog {:product-id vanilla})
                         [:found :current-price-cents]))
              "a failed outbox write rolls back its ledger and product update")
          (is (= 450
                 (get-in (catalog/change-price! catalog second-request)
                         [:accepted :current-price-cents]))
              "the same command can succeed after the failure because no ledger row leaked"))))))

(deftest closed-integration-contracts-reject-unknown-fields-test
  (fixture/with-system
    (fn [{:keys [ordering]}]
      (let [delivery {:headers {}
                      :message (assoc (catalog-contract/price-changed
                                       (random-uuid) (random-uuid)
                                       (random-uuid) (random-uuid)
                                       vanilla "vanilla" 300)
                                      :internal/debug true)}]
        (is (= :malformed (:rejected (ordering/receive! ordering delivery))))
        (is (= :price-unavailable
               (:rejected
                (ordering/place-order! ordering
                                       (place-order-request order-1 1)))))))))
