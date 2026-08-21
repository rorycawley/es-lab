(ns lab22.adapter-test
  "Driven-adapter contracts, separate from business behaviour."
  (:require [clojure.test :refer [deftest is testing]]
            [lab22.adapter.clock :as clock]
            [lab22.fixture :as fixture]
            [lab22.port :as port]
            [lab22.system :as system]))

(def stream-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def command-1 #uuid "0f1c2b3a-0000-4000-8000-000000000002")
(def event-1 #uuid "0f1c2b3a-0000-4000-8000-000000000003")
(def message-1 #uuid "0f1c2b3a-0000-4000-8000-000000000004")
(def t0 #inst "2026-09-01T09:00:00.000-00:00")
(def command {:command/id command-1 :command/type :example :data {}})
(def event {:event/id event-1 :event/type :example-recorded
            :event/occurred-at t0 :data {:value "kept"}})
(def message {:message-id message-1 :message-type :example-announcement
              :recipient :example-module :payload {:value "kept"}})
(defn- each-adapter [f]
  (doseq [[label make-system] (fixture/systems {:clock (clock/fixed-clock t0)})]
    (testing label
      (let [sys (system/start (make-system))]
        (try (f (system/app sys)) (finally (system/stop sys)))))))

(deftest event-store-adapters-preserve-the-port-contract-test
  (each-adapter
   (fn [{:keys [store]}]
     (let [[recorded] (port/append store stream-1 0 command [event])]
       (is (= event-1 (:event/id recorded)))
       (is (= :example-recorded (:event/type recorded)))
       (is (= {:value "kept"} (:data recorded)))
       (is (= stream-1 (:stream/id recorded)))
       (is (= 1 (:stream/version recorded)))
       (is (= (str command-1) (str (get-in recorded [:metadata :causation-id]))))
       (is (= [recorded] (port/read-stream store stream-1)))
       (is (= [recorded] (port/read-since store 0)))
       (is (= 1 (port/stream-version store stream-1)))))))
(deftest event-store-adapters-enforce-expected-version-test
  (each-adapter
   (fn [{:keys [store]}]
     (port/append store stream-1 0 command [event])
     (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Concurrent modification"
                           (port/append store stream-1 0 command
                                        [(assoc event :event/id (random-uuid))]))))))
(deftest outbox-adapters-preserve-message-content-test
  (each-adapter
   (fn [{:keys [outbox]}]
     (port/enqueue outbox [message])
     (let [[recorded] (port/pending outbox)]
       (is (= message-1 (:message-id recorded)))
       (is (= :example-announcement (keyword (:message-type recorded))))
       (is (= :example-module (keyword (:recipient recorded))))
       (is (= {:value "kept"} (:payload recorded)))))))
