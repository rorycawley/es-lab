(ns cart.slice.http-adapter-test
  (:require [cart.slice.add-product-item.adapter.in.http :as add-http]
            [cart.slice.add-product-item.port :as add-port]
            [cart.slice.view-cart.adapter.in.http :as view-http]
            [cart.slice.view-cart.port :as view-port]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]])
  (:import [java.util UUID]))

(def correlation-id (UUID/fromString "90000000-0000-0000-0000-000000000001"))

(defn- body [response]
  (json/parse-string (:body response) true))

(defn- add-stub [result]
  (reify add-port/AddProductItem
    (add-product-item [_ _]
      (if (instance? Throwable result) (throw result) result))))

(defn- view-stub [result]
  (reify view-port/ViewCart
    (view-cart [_ _]
      (if (instance? Throwable result) (throw result) result))))

(deftest add-http-adapter-maps-every-command-outcome
  (doseq [[outcome expected-status]
          [[{:outcome :success :result {:cart-id "10000000-0000-0000-0000-000000000001"}}
            200]
           [{:outcome :invalid :code :invalid-request :field-errors []} 400]
           [{:outcome :conflict :code :cart-changed
             :next-action :view-cart-before-retrying} 409]
           [{:outcome :rejected :code :cart-closed} 422]]]
    (testing (name (:outcome outcome))
      (let [response ((add-http/handler (add-stub outcome))
                      {:body-params {:request-id "ignored"}})]
        (is (= expected-status (:status response)))
        (is (= (json/parse-string (json/generate-string outcome) true)
               (body response))))))

  (testing "unexpected failure"
    (let [response ((add-http/handler
                     (add-stub (ex-info "storage failed" {}))
                     {:correlation-id-fn (constantly correlation-id)})
                    {:body-params {}})]
      (is (= 500 (:status response)))
      (is (= {:outcome "error"
              :code "internal-server-error"
              :correlation-id (str correlation-id)}
             (body response))))))

(deftest adapters-reject-malformed-json-before-dispatch
  (let [called?  (atom false)
        use-case (reify add-port/AddProductItem
                   (add-product-item [_ _]
                     (reset! called? true)))
        response ((add-http/handler use-case) {:body "{"})]
    (is (= 400 (:status response)))
    (is (= "invalid-json" (:code (body response))))
    (is (false? @called?))))

(deftest view-http-adapter-maps-query-outcomes
  (is (= 200 (:status ((view-http/handler
                        (view-stub {:outcome :success :result {}}))
                       {:body-params {}}))))
  (is (= 400 (:status ((view-http/handler
                        (view-stub {:outcome :invalid
                                    :code :invalid-cart
                                    :field-errors []}))
                       {:body-params {}})))))
