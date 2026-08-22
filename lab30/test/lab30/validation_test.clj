(ns lab30.validation-test
  "Malformed input is rejected at the public module boundary before SQL."
  (:require [clojure.test :refer [deftest is]]
            [lab30.fixture :as fixture]
            [lab30.registry.api :as registry]))

(deftest registration-and-search-requests-are-closed-test
  (fixture/with-registry
    (fn [module]
      (let [failure (try
                      (fixture/register! module "Muster" {:database-id 7})
                      (catch clojure.lang.ExceptionInfo e e))]
        (is (= :invalid-request (:reason (ex-data failure)))))
      (let [failure (try
                      (registry/search module {:query "Muster"
                                               :ui-language :en})
                      (catch clojure.lang.ExceptionInfo e e))]
        (is (= :invalid-request (:reason (ex-data failure))))))))

(deftest an-empty-name-after-boundary-cleaning-is-invalid-test
  (fixture/with-registry
    (fn [module]
      (let [failure (try
                      (fixture/register! module "\u0000  ")
                      (catch clojure.lang.ExceptionInfo e e))]
        (is (= :invalid-request (:reason (ex-data failure)))))
      (let [failure (try
                      (registry/search module {:query "\u0000  " :ui-language :fr})
                      (catch clojure.lang.ExceptionInfo e e))]
        (is (= :invalid-request (:reason (ex-data failure))))))))
