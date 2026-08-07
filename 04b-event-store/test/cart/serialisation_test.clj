(ns cart.serialisation-test
  "SPEC R6.3 — malli generates events, we push their storage representation
   through the JSON encoder and decoder, and assert we got back what storage
   promises to preserve."
  (:require [cart.schema :as schema]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [malli.generator :as mg]))

(defn- kw->str [k] (subs (str k) 1))

(defn- encode [x]
  (json/generate-string x))

(defn- decode [s]
  (json/parse-string s true))

(defn- event->stored [event]
  {:message-type     (kw->str (:type event))
   :message-data     (encode (:data event))
   :message-metadata (encode (:metadata event {}))})

(defn- stored->event [{:keys [message-type message-data message-metadata]}]
  (let [metadata (decode message-metadata)]
    (cond-> {:type (keyword message-type)
             :data (decode message-data)}
      (seq metadata) (assoc :metadata metadata))))

(defspec events-survive-the-storage-round-trip 500
  (prop/for-all [event (mg/generator schema/Event)]
                (= event (stored->event (event->stored event)))))

(defspec generated-events-match-their-schema 200
  (prop/for-all [event (mg/generator schema/Event)]
                (m/validate schema/Event event)))

(deftest event-type-is-reconstructed-from-message-type
  (let [event {:type :cart.event/confirmed
               :data {:cart-id "c1" :confirmed-at 1735689600000}}
        stored (event->stored event)]
    (is (= "cart.event/confirmed" (:message-type stored)))
    (is (= event (stored->event stored)))))

(deftest jsonb-data-is-queryable-plain-json
  (let [event {:type :cart.event/product-item-added
               :data {:cart-id "c1"
                      :product-item {:product-id "shoes" :quantity 1 :unit-price 1999}
                      :added-at 1735689600000}}
        stored (event->stored event)
        decoded (decode (:message-data stored))]
    (is (= "c1" (:cart-id decoded)))
    (is (= "shoes" (get-in decoded [:product-item :product-id])))
    (is (= 1999 (get-in decoded [:product-item :unit-price])))))

(deftest keyword-values-inside-json-become-strings
  (let [metadata {:now 1735689600000 :source :cart.source/web}]
    (is (= {:now 1735689600000 :source "cart.source/web"}
           (decode (encode metadata))))))

(deftest english-chinese-and-arabic-text-survives-json-round-trip
  (let [event {:type :cart.event/product-item-added
               :data {:cart-id "cart-English-购物车-عربة"
                      :product-item {:product-id "tea-茶-شاي"
                                     :quantity 1
                                     :unit-price 1999}
                      :added-at 1735689600000}
               :metadata {:now 1735689600000
                          :source "web-English-来源-مصدر"}}
        stored (event->stored event)]
    (is (= event (stored->event stored)))))
