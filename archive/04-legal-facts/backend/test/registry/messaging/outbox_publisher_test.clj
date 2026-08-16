(ns registry.messaging.outbox-publisher-test
  (:require [clojure.test :refer [deftest is]]
            [registry.messaging.outbox-publisher :as sut]
            [registry.messaging.rabbitmq-event-bus :as rmq]))

(defn- event [n]
  {:event/id n
   :event/type :test-event})

(defn- outbox-handle [events marked]
  {:fetch-unpublished (fn [_batch-size] events)
   :mark-published!   (fn [event-id] (swap! marked conj event-id))})

(deftest poll-cycle-publishes-and-marks-events-in-order
  (let [events    [(event 1) (event 2) (event 3)]
        published (atom [])
        marked    (atom [])]
    (with-redefs [rmq/publish-one! (fn [_conn event]
                                     (swap! published conj (:event/id event)))]
      (sut/run-poll-cycle! :conn (outbox-handle events marked) 50))

    (is (= [1 2 3] @published))
    (is (= [1 2 3] @marked))))

(deftest poll-cycle-stops-on-publish-failure
  (let [events    [(event 1) (event 2) (event 3)]
        published (atom [])
        marked    (atom [])]
    (with-redefs [rmq/publish-one! (fn [_conn event]
                                     (swap! published conj (:event/id event))
                                     (when (= 2 (:event/id event))
                                       (throw (ex-info "publish failed" {}))))]
      (sut/run-poll-cycle! :conn (outbox-handle events marked) 50))

    (is (= [1 2] @published))
    (is (= [1] @marked))))

(deftest poll-cycle-stops-on-mark-published-failure
  (let [events    [(event 1) (event 2) (event 3)]
        published (atom [])
        marked    (atom [])]
    (with-redefs [rmq/publish-one! (fn [_conn event]
                                     (swap! published conj (:event/id event)))]
      (sut/run-poll-cycle!
       :conn
       {:fetch-unpublished (fn [_batch-size] events)
        :mark-published!   (fn [event-id]
                             (when (= 2 event-id)
                               (throw (ex-info "mark failed" {})))
                             (swap! marked conj event-id))}
       50))

    (is (= [1 2] @published))
    (is (= [1] @marked))))
