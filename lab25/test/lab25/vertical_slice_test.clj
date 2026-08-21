(ns lab25.vertical-slice-test
  "Use-case behaviour through public module APIs and real Postgres.

  These slices deliberately own their SQL directly, so there is no repository
  port to fake. The tests remain coupled to business contracts, not SQL call
  choreography, but have integration-test cost and scope."
  (:require [clojure.test :refer [deftest is testing]]
            [lab25.catalog.api :as catalog]
            [lab25.fixture :as fixture]
            [lab25.ordering.api :as ordering]
            [lab25.system :as system]))

(def vanilla #uuid "0f1c2b3a-0000-4000-8000-000000000025")
(def order-1 #uuid "0f1c2b3a-0000-4000-8000-000000000101")
(def order-2 #uuid "0f1c2b3a-0000-4000-8000-000000000102")

(defn- change-vanilla! [catalog price-cents]
  (catalog/change-price! catalog {:product-id vanilla
                                  :product-name "vanilla"
                                  :price-cents price-cents}))

(deftest a-whole-slice-is-tested-through-its-module-api-test
  (fixture/with-system
    (fn [{:keys [catalog]}]
      (is (= {:product-id vanilla
              :product-name "vanilla"
              :current-price-cents 300}
             (dissoc (:accepted (change-vanilla! catalog 300)) :message-id)))
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
                                                 {:order-id order-1
                                                  :product-id vanilla
                                                  :quantity 2})))))

      (testing "the outbox crosses the boundary"
        (is (= 1 (count (system/relay-catalog! app))))
        (is (= 600
               (get-in (ordering/place-order! ordering
                                              {:order-id order-1
                                               :product-id vanilla
                                               :quantity 2})
                       [:accepted :total-cents])))))))

(deftest duplicated-data-has-different-meaning-test
  (fixture/with-system
    (fn [{:keys [catalog ordering] :as app}]
      (change-vanilla! catalog 300)
      (system/relay-catalog! app)
      (ordering/place-order! ordering {:order-id order-1
                                       :product-id vanilla
                                       :quantity 1})

      (change-vanilla! catalog 450)
      (system/relay-catalog! app)
      (ordering/place-order! ordering {:order-id order-2
                                       :product-id vanilla
                                       :quantity 1})

      (is (= 450 (get-in (catalog/get-product catalog {:product-id vanilla})
                         [:found :current-price-cents]))
          "Catalog owns today's price")
      (is (= 300 (get-in (ordering/get-order ordering {:order-id order-1})
                         [:found :unit-price-cents]))
          "the first order owns the price agreed then")
      (is (= 450 (get-in (ordering/get-order ordering {:order-id order-2})
                         [:found :unit-price-cents]))))))

(deftest the-inbox-makes-contract-redelivery-idempotent-test
  (fixture/with-system
    (fn [{:keys [catalog ordering] :as app}]
      (change-vanilla! catalog 300)
      (let [message (get-in (first (system/relay-catalog! app)) [:message])]
        (is (= (:message/id message)
               (:duplicate (ordering/receive! ordering message))))
        (is (empty? (system/relay-catalog! app))
            "the outbox has already marked this delivery")))))

(deftest validation-is-a-behaviour-around-the-slice-test
  (fixture/with-system
    (fn [{:keys [catalog]}]
      (is (= :malformed
             (:rejected (catalog/change-price! catalog
                                               {:product-id vanilla
                                                :product-name "vanilla"
                                                :price-cents 0}))))
      (is (= vanilla (:not-found (catalog/get-product catalog
                                                      {:product-id vanilla}))))
      (is (= [{:request :catalog/change-price :outcome :malformed}
              {:request :catalog/get-product :outcome :completed}]
             (catalog/audit-log catalog))))))
