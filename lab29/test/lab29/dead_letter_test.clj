(ns lab29.dead-letter-test
  "When retrying cannot help, and who it cannot help.

  Lab 28 gave up on a *message*. This gives up on a **delivery**, which is the
  only version of giving up that is safe when a message has more than one
  consumer: the graveyard names both the message and who would not take it,
  and everyone else is untouched."
  (:require [clojure.test :refer [deftest is testing]]
            [lab29.catalog.api :as catalog]
            [lab29.fixture :as fixture]
            [lab29.ordering.api :as ordering]
            [lab29.platform.relay :as relay]
            [lab29.system :as system]))

(def vanilla #uuid "0f1c2b3a-0000-4000-8000-000000000026")

(defn- stock! [catalog price-cents]
  (catalog/change-price! catalog {:command-id (random-uuid)
                                  :correlation-id (random-uuid)
                                  :product-id vanilla
                                  :product-name "vanilla"
                                  :price-cents price-cents}))

(defn- broken [reason]
  (fn [_] (throw (ex-info "I will never accept this" {:reason reason}))))

;; ---------------------------------------------------------------------------

(deftest a-delivery-nobody-will-accept-eventually-stops-being-tried-test
  (fixture/with-system {:handlers {:ordering (broken :consumer-broken)
                                   :websub   (broken :consumer-broken)}}
    (fn [{:keys [catalog] :as app}]
      (stock! catalog 300)

      (testing "the first passes record the failure and leave the delivery queued"
        (dotimes [pass (dec relay/attempts-before-death)]
          (let [summary (system/relay-catalog! app)]
            (is (empty? (:delivered summary)) (str "pass " pass))
            (is (= 2 (count (:failed summary))) "one failure per consumer")
            (is (empty? (:dead-lettered summary))))))

      (testing "and then both are moved out of the way, separately"
        (let [summary (system/relay-catalog! app)]
          (is (= 2 (count (:dead-lettered summary))))
          (is (= #{:ordering :websub}
                 (set (map :consumer (:dead-lettered summary)))))))

      (testing "the queue is empty, so nothing behind it is stuck"
        (is (= {:delivered [] :failed [] :dead-lettered []}
               (system/relay-catalog! app))))

      (testing "and the graveyard names the message and who refused it"
        (let [letters (catalog/dead-letters catalog)]
          (is (= 2 (count letters)))
          (doseq [dead letters]
            (is (= "catalog/price-changed" (:message-type dead)))
            (is (= "integration-event" (:message-kind dead)))
            (is (re-find #"consumer-broken" (:last-error dead)))
            (is (= :catalog/price-changed (get-in dead [:message :event/type]))
                "the body kept its namespaces, which JSON would have dropped")))))))

(deftest one-poison-message-does-not-block-the-ones-behind-it-test
  (let [seen  (atom [])
        calls (atom 0)]
    (fixture/with-system
      {:handlers {:ordering (fn [{:keys [message]}]
                              (if (= 1 (swap! calls inc))
                                (throw (ex-info "not this one" {:reason :poison}))
                                (swap! seen conj (get-in message [:payload :price-cents]))))
                  :websub   (fn [_] {:accepted true})}}
      (fn [{:keys [catalog] :as app}]
        (stock! catalog 300)
        (stock! catalog 450)
        (stock! catalog 500)
        (let [summary (system/relay-catalog! app)]
          (is (= 2 (count (filter #(= :ordering (:consumer %)) (:delivered summary)))))
          (is (= 1 (count (:failed summary))))
          (is (= [450 500] @seen)
              "the two behind the poison message went out on the same pass"))))))

(deftest a-dead-letter-can-be-revived-test
  (let [broken? (atom true)
        seen    (atom [])]
    (fixture/with-system
      {:handlers {:ordering (fn [{:keys [message]}]
                              (if @broken?
                                (throw (ex-info "still broken" {:reason :consumer-broken}))
                                (swap! seen conj (get-in message [:payload :price-cents]))))
                  :websub   (fn [_] {:accepted true})}}
      (fn [{:keys [catalog] :as app}]
        (stock! catalog 300)
        (dotimes [_ relay/attempts-before-death] (system/relay-catalog! app))
        (let [[dead] (catalog/dead-letters catalog)]
          (is (= :ordering (:consumer dead)))

          (testing "the body kept enough to send it again"
            (let [{:keys [revived consumer]} (catalog/revive! catalog (:message-id dead)
                                                              (:consumer dead))]
              (is (= (:message-id dead) revived))
              (is (= :ordering consumer))))

          (testing "and once the consumer is fixed it goes"
            (reset! broken? false)
            (is (= 1 (count (:delivered (system/relay-catalog! app)))))
            (is (= [300] @seen))
            (is (empty? (catalog/dead-letters catalog)))))))))

(deftest a-healthy-queue-has-an-empty-graveyard-test
  (fixture/with-system
    (fn [{:keys [catalog ordering] :as app}]
      (stock! catalog 300)
      (system/relay-all! app)
      (is (empty? (catalog/dead-letters catalog)))
      (is (empty? (ordering/dead-letters ordering))))))
