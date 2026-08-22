(ns lab29.websub-test
  "The external boundary, where the subscriber is a stranger.

  Everything internal to this monolith is delivered because a dispatcher
  routed it: the sender is trusted, the recipient is known, and the failure
  modes are ours. None of that survives contact with the public internet, and
  each mechanism below is a consequence of one thing that stops being true."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [lab29.catalog.api :as catalog]
            [lab29.fake-subscriber :as subscriber]
            [lab29.fixture :as fixture]
            [lab29.system :as system]
            [lab29.websub.hub :as hub]
            [lab29.websub.signature :as signature]
            [lab29.websub.topics :as topics])
  (:import (java.time Instant)))

(def vanilla #uuid "0f1c2b3a-0000-4000-8000-000000000026")

(defn- stock! [catalog price-cents]
  (catalog/change-price! catalog {:command-id (random-uuid)
                                  :correlation-id (random-uuid)
                                  :product-id vanilla
                                  :product-name "vanilla"
                                  :price-cents price-cents}))

(defn- describe! [catalog description]
  (catalog/describe-product! catalog {:command-id (random-uuid)
                                      :correlation-id (random-uuid)
                                      :product-id vanilla
                                      :description description}))

(defn- topic-of [app] (topics/topic-url (:base-url app) vanilla))

(defn- subscribe! [app sub & {:keys [lease] :or {lease 3600}}]
  (hub/subscribe! (:websub-hub app)
                  {:topic (topic-of app) :callback (:callback sub)
                   :secret (:secret sub) :lease-seconds lease}))

;; ---------------------------------------------------------------------------
;; Verification of intent
;; ---------------------------------------------------------------------------

(deftest a-subscription-exists-only-once-the-callback-proves-it-asked-test
  ;; Without this, `hub.callback` is an amplification weapon: point it at
  ;; somebody else's server and the hub becomes the attacker.
  (fixture/with-system
    (fn [app]
      (let [sub (subscriber/start!)]
        (try
          (is (:accepted (subscribe! app sub)))
          (is (= [{:mode "subscribe" :topic (topic-of app)}]
                 (subscriber/verifications sub))
              "the hub asked before it believed")
          (is (= 1 (count (hub/live (:websub-hub app) (topic-of app)))))
          (finally (subscriber/stop! sub)))))))

(deftest a-callback-that-denies-asking-gets-no-subscription-test
  (fixture/with-system
    (fn [app]
      (let [sub (subscriber/start!)]
        (try
          (subscriber/refuse-verification! sub)
          (is (= :verification-failed (:rejected (subscribe! app sub))))
          (is (empty? (hub/live (:websub-hub app) (topic-of app)))
              "and nothing was recorded, so nothing will ever be delivered")
          (finally (subscriber/stop! sub)))))))

(deftest unsubscribing-is-verified-too-test
  ;; Nobody else gets to cancel your subscription either.
  (fixture/with-system
    (fn [app]
      (let [sub (subscriber/start!)]
        (try
          (subscribe! app sub)
          (subscriber/refuse-verification! sub)
          (is (= :verification-failed
                 (:rejected (hub/unsubscribe! (:websub-hub app)
                                              {:topic (topic-of app)
                                               :callback (:callback sub)}))))
          (is (= 1 (count (hub/live (:websub-hub app) (topic-of app))))
              "still subscribed, because the request was not proved")
          (finally (subscriber/stop! sub)))))))

;; ---------------------------------------------------------------------------
;; Distribution
;; ---------------------------------------------------------------------------

(deftest a-price-change-reaches-a-verified-subscriber-signed-test
  (fixture/with-system
    (fn [{:keys [catalog] :as app}]
      (let [sub (subscriber/start!)]
        (try
          (subscribe! app sub)
          (stock! catalog 300)
          (system/relay-catalog! app)

          (let [[push] (subscriber/received sub)]
            (is (some? push) "the subscriber was pushed to")
            (is (:genuine? push)
                "and the body was signed with that subscriber's own secret")
            (is (str/includes? (:links push) "rel=\"hub\"")
                "with the hub named, so a subscriber can renew without guessing")
            (let [body (json/read-str (:body push) :key-fn keyword)]
              (is (= "vanilla" (:name body)))
              (is (= 300 (:price-cents body)))))
          (finally (subscriber/stop! sub)))))))

(deftest an-unverified-or-expired-subscription-receives-nothing-test
  (fixture/with-system
    (fn [{:keys [catalog] :as app}]
      (let [sub (subscriber/start!)]
        (try
          (subscribe! app sub :lease 1)
          ;; A lease is how a hub stops delivering to a subscriber that has
          ;; quietly gone away. Nobody unsubscribes politely.
          (let [expired (assoc (:websub-hub app)
                               :clock #(.plusSeconds (Instant/now) 3600))]
            (is (empty? (hub/live expired (topic-of app)))))
          (stock! catalog 300)
          (with-redefs [hub/live (fn [& _] [])]
            (system/relay-catalog! app))
          (is (empty? (subscriber/received sub)))
          (finally (subscriber/stop! sub)))))))

(deftest a-subscriber-that-keeps-refusing-is-dropped-test
  ;; The hub's version of a dead letter. A callback that has stopped working
  ;; is not retried forever, because the hub has other subscribers and a
  ;; finite amount of patience.
  (fixture/with-system
    (fn [{:keys [catalog] :as app}]
      (let [sub (subscriber/start!)]
        (try
          (subscribe! app sub)
          (subscriber/refuse-delivery! sub)
          (dotimes [n 3]
            (stock! catalog (+ 300 n))
            (system/relay-catalog! app))
          (is (empty? (hub/live (:websub-hub app) (topic-of app)))
              "dropped, rather than pushed to forever")
          (finally (subscriber/stop! sub)))))))

;; ---------------------------------------------------------------------------
;; Topics are resources, not facts
;; ---------------------------------------------------------------------------

(deftest two-facts-become-one-resource-test
  ;; Catalog publishes what happened. The adapter maintains what *is*. A
  ;; subscriber receives the second, which is why a missed push is survivable
  ;; and a missed domain event would not be.
  (fixture/with-system
    (fn [{:keys [catalog websub] :as app}]
      (stock! catalog 300)
      (describe! catalog "a creamy vanilla flavour")
      (system/relay-catalog! app)
      (let [now (topics/representation (:datasource websub) vanilla)]
        (is (= "vanilla" (:name now)))
        (is (= "a creamy vanilla flavour" (:description now)))
        (is (= 300 (:price-cents now)))
        (is (= 2 (:version now))
            "one resource, created by the first fact and advanced by the second")))))

(deftest the-topic-is-a-public-resource-with-discovery-on-it-test
  (fixture/with-system
    (fn [{:keys [catalog] :as app}]
      (stock! catalog 300)
      (system/relay-catalog! app)
      (let [handler  (system/handler app {:signing-secret "unused" :now (constantly 0)})
            response (handler {:request-method :get
                               :uri (str "/v1/products/" vanilla)
                               :path-params {:product-id (str vanilla)}})]
        (is (= 200 (:status response)))
        (is (str/includes? (get-in response [:headers "Link"]) "rel=\"hub\"")
            "a subscriber can find the hub from the topic alone")
        (is (str/includes? (get-in response [:headers "Link"]) "rel=\"self\""))))))

;; ---------------------------------------------------------------------------
;; What a stranger is allowed to see
;; ---------------------------------------------------------------------------

(deftest the-cost-price-never-leaves-the-building-test
  ;; Labs 15, 24, 26 and 27 each kept a field out of one more place. This is
  ;; the boundary where the reader is a stranger, and a public projection is
  ;; the easiest of the five to leak through, because nobody reviewing a
  ;; projection thinks of it as an API.
  (fixture/with-system
    (fn [{:keys [catalog websub] :as app}]
      (let [sub (subscriber/start!)]
        (try
          (subscribe! app sub)
          (stock! catalog 300)
          (describe! catalog "a creamy vanilla flavour")
          (system/relay-catalog! app)

          (let [pushed  (map :body (subscriber/received sub))
                topic   (topics/body (topics/representation (:datasource websub) vanilla))
                emitted (conj (vec pushed) topic)]
            (is (seq pushed) "something really was published")
            (doseq [body emitted]
              (is (not (str/includes? body "supplier"))
                  "not the field name")
              (is (not (str/includes? body "cost"))
                  "not the concept")))
          (finally (subscriber/stop! sub)))))))

(deftest a-signature-is-per-subscriber-not-per-hub-test
  ;; A leaked secret compromises one subscription rather than all of them.
  (let [body "{\"price-cents\":300}"]
    (is (signature/verify "s3cret" (signature/sign "s3cret" body) body))
    (is (not (signature/verify "other" (signature/sign "s3cret" body) body)))
    (is (not (signature/verify "s3cret" nil body)))
    (is (str/starts-with? (signature/sign "s3cret" body) "sha256=")
        "the method is named in the header, because WebSub allows others")))
