(ns lab21.adapter-test
  "One neutral driven-port contract, run against memory and PostgreSQL."
  (:require [clojure.test :refer [deftest is testing]]
            [lab21.adapter.clock :as clock]
            [lab21.fixture :as fixture]
            [lab21.port :as port]
            [lab21.system :as system]))

(def stream-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def command-1 #uuid "0f1c2b3a-0000-4000-8000-000000000002")
(def correlation-1 #uuid "0f1c2b3a-0000-4000-8000-000000000003")
(def event-1 #uuid "0f1c2b3a-0000-4000-8000-000000000004")
(def message-1 #uuid "0f1c2b3a-0000-4000-8000-000000000005")
(def t0 #inst "2026-09-01T09:00:00.000-00:00")

(def command
  {:command/id command-1
   :command/type :example
   :correlation-id correlation-1
   :data {:value "requested"}})

(def event
  {:event/id event-1
   :event/type :example-recorded
   :event/occurred-at t0
   :data {:value "kept"}
   :metadata {:causation-id command-1 :correlation-id correlation-1}})

(def message
  {:message-id message-1
   :message-type :example-announcement
   :recipient :example-module
   :causation-id event-1
   :correlation-id correlation-1
   :payload {:value "kept"}})

(defn- each-adapter [f]
  (doseq [[label make-system] (fixture/systems {:clock (clock/fixed-clock t0)})]
    (testing label
      (let [sys (system/start (make-system))]
        (try
          (f (system/app sys))
          (finally (system/stop sys)))))))

(deftest adapters-preserve-the-atomic-command-outcome-contract-test
  (each-adapter
   (fn [{:keys [store outbox]}]
     (let [[recorded] (port/commit-command
                       store stream-1 0 command [event] [message])]
       (is (= event-1 (:event/id recorded)))
       (is (= :example-recorded (:event/type recorded)))
       (is (= {:value "kept"} (:data recorded)))
       (is (= stream-1 (:stream/id recorded)))
       (is (= 1 (:stream/version recorded)))
       (is (= command-1 (get-in recorded [:metadata :causation-id])))
       (is (= correlation-1 (get-in recorded [:metadata :correlation-id])))
       (is (= [recorded] (port/read-stream store stream-1)))
       (is (= [recorded] (port/read-since store 0)))
       (is (= 1 (port/stream-version store stream-1)))
       (is (= message (first (port/pending outbox))))))))

(deftest exact-command-retries-return-the-original-outcome-test
  (each-adapter
   (fn [{:keys [store outbox]}]
     (let [first-result (port/commit-command
                         store stream-1 0 command [event] [message])
           retry-result (port/commit-command
                         store stream-1 0 command
                         [(assoc event :event/id (random-uuid))]
                         [(assoc message :message-id (random-uuid))])]
       (is (= first-result retry-result))
       (is (= 1 (count (port/read-stream store stream-1))))
       (is (= 1 (count (port/pending outbox))))))))

(deftest command-id-reuse-for-another-request-is-rejected-test
  (each-adapter
   (fn [{:keys [store]}]
     (port/commit-command store stream-1 0 command [event] [])
     (let [failure (try
                     (port/commit-command
                      store stream-1 1 (assoc command :data {:value "different"}) [] [])
                     (catch clojure.lang.ExceptionInfo e e))]
       (is (= :command-id-collision (:reason (ex-data failure))))))))

(deftest adapters-reject-stale-and-future-expected-versions-test
  (each-adapter
   (fn [{:keys [store]}]
     (port/commit-command store stream-1 0 command [event] [])
     (doseq [expected [0 5]]
       (let [next-command (assoc command :command/id (random-uuid))
             failure (try
                       (port/commit-command
                        store stream-1 expected next-command
                        [(assoc event :event/id (random-uuid)
                                :metadata {:causation-id (:command/id next-command)
                                           :correlation-id correlation-1})]
                        [])
                       (catch clojure.lang.ExceptionInfo e e))]
         (is (= :concurrent-modification (:reason (ex-data failure)))))))))

(deftest a-bad-message-cannot-leave-an-event-without-its-outbox-row-test
  (each-adapter
   (fn [{:keys [store outbox]}]
     (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Invalid message"
          (port/commit-command store stream-1 0 command [event]
                               [(dissoc message :recipient)])))
     (is (empty? (port/read-stream store stream-1)))
     (is (empty? (port/pending outbox))))))

(deftest an-outbox-identity-conflict-rolls-back-the-new-fact-test
  (each-adapter
   (fn [{:keys [store outbox]}]
     (port/commit-command store stream-1 0 command [event] [message])
     (let [stream-2 #uuid "0f1c2b3a-0000-4000-8000-000000000101"
           command-2 (assoc command :command/id
                            #uuid "0f1c2b3a-0000-4000-8000-000000000102")
           event-2 (assoc event
                          :event/id #uuid "0f1c2b3a-0000-4000-8000-000000000103"
                          :metadata {:causation-id (:command/id command-2)
                                     :correlation-id correlation-1})
           failure (try
                     (port/commit-command store stream-2 0 command-2 [event-2]
                                          [(assoc message :causation-id (:event/id event-2))])
                     (catch clojure.lang.ExceptionInfo e e))]
       (is (= :duplicate-message-id (:reason (ex-data failure))))
       (is (empty? (port/read-stream store stream-2)))
       (is (= 1 (count (port/pending outbox))))
       (is (nil? (port/command-result store stream-2 command-2)))))))

(deftest zero-event-outcomes-are-ledger-backed-test
  (each-adapter
   (fn [{:keys [store]}]
     (is (= [] (port/commit-command store stream-1 0 command [] [])))
     (is (= [] (port/commit-command store stream-1 0 command [] []))))))
