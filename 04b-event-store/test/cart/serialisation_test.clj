(ns cart.serialisation-test
  "SPEC R6.3 — malli generates events, we push them through the real encoder
   and decoder, and assert we got back what we put in. Catches keyword,
   timestamp and numeric drift in one property."
  (:require [cart.schema :as schema]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [cognitect.transit :as transit]
            [malli.core :as m]
            [malli.generator :as mg])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(defn- encode ^String [x]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json) x)
    (.toString out "UTF-8")))

(defn- decode [^String s]
  (transit/read (transit/reader (ByteArrayInputStream. (.getBytes s "UTF-8")) :json)))

(defspec events-survive-a-round-trip 500
  (prop/for-all [event (mg/generator schema/Event)]
                (= event (decode (encode event)))))

(defspec generated-events-match-their-schema 200
  (prop/for-all [event (mg/generator schema/Event)]
                (m/validate schema/Event event)))

(deftest namespaced-keywords-survive
  (let [event {:type :cart.event/confirmed
               :data {:cart-id "c1" :confirmed-at 1735689600000}}]
    (is (= event (decode (encode event))))
    (is (keyword? (:type (decode (encode event))))
        "plain JSON would return the string \"cart.event/confirmed\" here")))

(deftest money-stays-an-integer
  (let [event {:type :cart.event/product-item-added
               :data {:cart-id "c1"
                      :product-item {:product-id "shoes" :quantity 1 :unit-price 1999}
                      :added-at 1735689600000}}
        back  (decode (encode event))]
    (is (integer? (get-in back [:data :product-item :unit-price])))
    (is (= 1999 (get-in back [:data :product-item :unit-price])))))
