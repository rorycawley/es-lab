(ns lab29.dispatcher-test
  "Two verbs, and the four ways a routing table can be wrong.

  None of this needs a database. Routing is a property of what the modules
  declare, so it can be asserted with values -- which also means the checks
  run at startup rather than at the moment a message has nowhere to go."
  (:require [clojure.test :refer [deftest is testing]]
            [lab29.platform.contract :as contract]
            [lab29.platform.dispatcher :as dispatcher]
            [lab29.platform.message :as message]))

(def ^:private ids {:causation-id (random-uuid) :correlation-id (random-uuid)})

(defn- contract-for [module & {:as decl}]
  (merge {:module module :handles-commands #{} :consumes-events #{}
          :publishes-events #{} :provides-queries #{}}
         decl))

(def ^:private sound
  [(contract-for :catalog :publishes-events #{:catalog/price-changed})
   (contract-for :ordering :consumes-events #{:catalog/price-changed})
   (contract-for :websub :consumes-events #{:catalog/price-changed})
   (contract-for :payments :handles-commands #{:payments/charge-order})])

;; ---------------------------------------------------------------------------
;; Cardinality
;; ---------------------------------------------------------------------------

(deftest a-command-has-exactly-one-destination-test
  (let [d (dispatcher/dispatcher sound {})]
    (is (= #{:payments}
           (dispatcher/consumers
            d (message/command (random-uuid) :payments/charge-order ids {}))))))

(deftest a-command-nobody-handles-is-a-routing-error-test
  ;; The difference that matters. Nobody listening to a fact is a legitimate
  ;; state; nobody listening to a request is a request that will never happen,
  ;; and silence is the worst way to find out.
  (let [d (dispatcher/dispatcher sound {})
        failure (try (dispatcher/consumers
                      d (message/command (random-uuid) :payments/refund ids {}))
                     (catch clojure.lang.ExceptionInfo e e))]
    (is (= :no-destination (:reason (ex-data failure))))))

(deftest an-event-may-have-many-consumers-or-none-test
  (let [d (dispatcher/dispatcher sound {})]
    (testing "many"
      (is (= #{:ordering :websub}
             (dispatcher/consumers
              d (message/integration-event
                 (random-uuid) :catalog/price-changed ids {})))))
    (testing "and none, which is not an error"
      (is (= #{}
             (dispatcher/consumers
              d (message/integration-event
                 (random-uuid) :catalog/nobody-cares ids {})))
          "the producer does not know or care who cares"))))

;; ---------------------------------------------------------------------------
;; The four ways a contract set can fail to add up
;; ---------------------------------------------------------------------------

(defn- refuses [contracts]
  (-> (try (contract/routes contracts) (catch clojure.lang.ExceptionInfo e e))
      ex-data :problems first))

(deftest a-command-with-two-handlers-refuses-to-start-test
  ;; Two modules answering one request is not fan-out. It is an unresolved
  ;; argument about who owns the capability, and it would show up as a double
  ;; charge rather than as an error.
  (is (re-find #"handled by"
               (refuses [(contract-for :payments :handles-commands #{:x/do-it})
                         (contract-for :billing :handles-commands #{:x/do-it})]))))

(deftest an-event-with-two-publishers-refuses-to-start-test
  (is (re-find #"published by"
               (refuses [(contract-for :a :publishes-events #{:x/happened})
                         (contract-for :b :publishes-events #{:x/happened})]))))

(deftest subscribing-to-something-nobody-sends-refuses-to-start-test
  ;; A subscription to an event that does not exist is a silent no-op -- a
  ;; consumer that will simply never run, usually after a rename.
  (is (re-find #"published by nobody"
               (refuses [(contract-for :ordering
                                       :consumes-events #{:catalog/price-chnaged})]))))

(deftest a-type-that-is-both-refuses-to-start-test
  (is (re-find #"both a command and an event"
               (refuses [(contract-for :a :handles-commands #{:x/thing})
                         (contract-for :b :publishes-events #{:x/thing})]))))

(deftest a-sound-set-of-contracts-produces-the-routing-table-test
  (is (= {:commands {:payments/charge-order :payments}
          :events   {:catalog/price-changed #{:ordering :websub}}
          :queries  {}}
         (contract/routes sound))))

;; ---------------------------------------------------------------------------
;; Delivery
;; ---------------------------------------------------------------------------

(deftest the-two-verbs-deliver-differently-test
  (let [seen (atom [])
        d    (dispatcher/dispatcher
              sound
              {:payments (fn [m] (swap! seen conj [:payments m]) :charged)
               :ordering (fn [m] (swap! seen conj [:ordering m]) :priced)
               :websub   (fn [m] (swap! seen conj [:websub m]) :published)})]
    (testing "one command, one handler, one result"
      (is (= {:consumer :payments :result :charged}
             (dispatcher/send-command!
              d (message/command (random-uuid) :payments/charge-order ids {})))))
    (testing "one event, every subscriber, no single result"
      (is (= [{:consumer :ordering :result :priced}
              {:consumer :websub :result :published}]
             (dispatcher/publish-event!
              d (message/integration-event
                 (random-uuid) :catalog/price-changed ids {})))))))

(deftest an-envelope-says-which-kind-it-is-test
  ;; There is no generic constructor, so the decision cannot be deferred to
  ;; whoever has to route the thing later.
  (let [c (message/command (random-uuid) :payments/charge-order ids {:order-id 1})
        e (message/integration-event (random-uuid) :catalog/price-changed ids {:fact-id 2})]
    (is (message/command? c))
    (is (message/event? e))
    (is (= :data (if (message/command? c) :data :payload)))
    (is (contains? c :data) "a command carries :data -- lab 2's word")
    (is (contains? e :payload) "an event in transit carries :payload -- lab 3's")
    (is (= :payments/charge-order (message/message-type c)))
    (is (= :catalog/price-changed (message/message-type e)))))
