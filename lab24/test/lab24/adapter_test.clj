(ns lab24.adapter-test
  "Neutral driven-port semantics against memory and PostgreSQL."
  (:require [clojure.test :refer [deftest is testing]]
            [lab24.adapter.clock :as clock]
            [lab24.fixture :as fixture]
            [lab24.port.driven :as driven]
            [lab24.system :as system]))

(def stream-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def command-1 #uuid "0f1c2b3a-0000-4000-8000-000000000002")
(def correlation-1 #uuid "0f1c2b3a-0000-4000-8000-000000000003")
(def event-1 #uuid "0f1c2b3a-0000-4000-8000-000000000004")
(def message-1 #uuid "0f1c2b3a-0000-4000-8000-000000000005")
(def t0 #inst "2026-09-01T09:00:00.000-00:00")
(def actor {:type "user" :id "USR-11902"})

(def command {:command/id command-1 :command/type :example
              :correlation-id correlation-1 :command/actor actor
              :data {:value "requested"}})
(def event {:event/id event-1 :event/type :example-recorded :event/occurred-at t0
            :data {:value "kept"}
            :metadata {:causation-id command-1 :correlation-id correlation-1
                       :actor actor}})
(def message {:message-id message-1 :message-type :example-announcement
              :recipient :example-module :causation-id event-1
              :correlation-id correlation-1 :payload {:value "kept"}})

(defn- each-adapter [f]
  (doseq [[label make-system] (fixture/systems {:clock (clock/fixed-clock t0)})]
    (testing label
      (let [sys (system/start (make-system))]
        (try (f (system/app sys)) (finally (system/stop sys)))))))

(deftest complete-command-outcomes-round-trip-test
  (each-adapter
   (fn [{:keys [store outbox]}]
     (let [[recorded] (driven/commit-command store stream-1 0 command [event] [message])]
       (is (= event-1 (:event/id recorded)))
       (is (= :example-recorded (:event/type recorded)))
       (is (= {:value "kept"} (:data recorded)))
       (is (= stream-1 (:stream/id recorded)))
       (is (= 1 (:stream/version recorded)))
       (is (= command-1 (get-in recorded [:metadata :causation-id])))
       (is (= correlation-1 (get-in recorded [:metadata :correlation-id])))
       (is (= actor (get-in recorded [:metadata :actor])))
       (is (= [recorded] (driven/read-stream store stream-1)))
       (is (= [recorded] (driven/read-since store 0)))
       (is (= message (first (driven/pending outbox))))))))

(deftest exact-command-retries-return-the-original-outcome-test
  (each-adapter
   (fn [{:keys [store outbox]}]
     (let [first-result (driven/commit-command store stream-1 0 command [event] [message])
           retry-result (driven/commit-command
                         store stream-1 0 command
                         [(assoc event :event/id (random-uuid))]
                         [(assoc message :message-id (random-uuid))])]
       (is (= first-result retry-result))
       (is (= 1 (count (driven/read-stream store stream-1))))
       (is (= 1 (count (driven/pending outbox))))))))

(deftest command-id-collisions-are-not-retries-test
  (each-adapter
   (fn [{:keys [store]}]
     (driven/commit-command store stream-1 0 command [event] [])
     (let [failure (try
                     (driven/command-result store stream-1
                                            (assoc command :data {:value "different"}))
                     (catch clojure.lang.ExceptionInfo e e))]
       (is (= :command-id-collision (:reason (ex-data failure))))))))

(deftest changing-the-actor-is-not-an-exact-command-retry-test
  (each-adapter
   (fn [{:keys [store]}]
     (driven/commit-command store stream-1 0 command [event] [])
     (let [failure (try
                     (driven/command-result
                      store stream-1
                      (assoc command :command/actor {:type "user" :id "USR-83721"}))
                     (catch clojure.lang.ExceptionInfo e e))]
       (is (= :command-id-collision (:reason (ex-data failure))))))))

(deftest stale-and-future-versions-are-rejected-test
  (each-adapter
   (fn [{:keys [store]}]
     (driven/commit-command store stream-1 0 command [event] [])
     (doseq [expected [0 5]]
       (let [cmd (assoc command :command/id (random-uuid))
             fact (assoc event :event/id (random-uuid)
                         :metadata {:causation-id (:command/id cmd)
                                    :correlation-id correlation-1
                                    :actor actor})
             failure (try (driven/commit-command store stream-1 expected cmd [fact] [])
                          (catch clojure.lang.ExceptionInfo e e))]
         (is (= :concurrent-modification (:reason (ex-data failure)))))))))

(deftest an-outbox-conflict-rolls-back-the-event-and-ledger-test
  (each-adapter
   (fn [{:keys [store outbox]}]
     (driven/commit-command store stream-1 0 command [event] [message])
     (let [stream-2 #uuid "0f1c2b3a-0000-4000-8000-000000000101"
           cmd (assoc command :command/id #uuid "0f1c2b3a-0000-4000-8000-000000000102")
           fact (assoc event :event/id #uuid "0f1c2b3a-0000-4000-8000-000000000103"
                       :metadata {:causation-id (:command/id cmd)
                                  :correlation-id correlation-1
                                  :actor actor})
           failure (try
                     (driven/commit-command store stream-2 0 cmd [fact]
                                            [(assoc message :causation-id (:event/id fact))])
                     (catch clojure.lang.ExceptionInfo e e))]
       (is (= :duplicate-message-id (:reason (ex-data failure))))
       (is (empty? (driven/read-stream store stream-2)))
       (is (= 1 (count (driven/pending outbox))))
       (is (nil? (driven/command-result store stream-2 cmd)))))))

(deftest zero-event-outcomes-are-ledger-backed-test
  (each-adapter
   (fn [{:keys [store]}]
     (is (= [] (driven/commit-command store stream-1 0 command [] [])))
     (is (= [] (driven/command-result store stream-1 command))))))
