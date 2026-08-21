(ns lab24.app-test
  "One suite, two adapters.

  A port with a single implementation is not a boundary — it is indirection
  with optimism attached. These tests run identically against a map in an atom
  and against Postgres in a container, and the fact that they *can* is the only
  evidence that the boundary is real.

  Note what the tests never mention: `next.jdbc`, a datasource, a container, a
  transaction — and now also a token, an identity provider or a role. This
  layer is beneath authentication, and driving it here is how the suite stays
  free of both databases and logins."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [lab24.adapter.clock :as clock]
            [lab24.app :as app]
            [lab24.core.contract :as contract]
            [lab24.core.truck :as truck]
            [lab24.fixture :as fixture]
            [lab24.port.driven :as driven]
            [lab24.system :as system]))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")
(def t0 #inst "2026-09-01T09:00:00.000-00:00")

;; The actor is a plain value down here. It arrives from a verified token in
;; production and from this map in a test, and `decide` cannot tell.
(def dana {:type "user" :id "USR-83721"})
(def rudi {:type "user" :id "USR-11902"})

(defn- command
  ([type data] (command rudi type data))
  ([actor type data]
   {:command/id (random-uuid) :command/type type :command/actor actor :data data}))

(defn- rostered
  "Put Dana on the truck. Selling needs a driver assigned now, so most tests
  start here — which is itself the ABAC rule showing up as a precondition."
  [app]
  (app/handle app truck-1 (command :assign-driver {:driver-id (:id dana)}))
  app)

(defn- each-adapter
  "Run `f` against every adapter, so a failure names which one broke."
  [f]
  (doseq [[label make-system] (fixture/systems {:clock (clock/fixed-clock t0)})]
    (testing (str "against " label)
      (let [sys (system/start (make-system))]
        (try (f (system/app sys))
             (finally (system/stop sys)))))))

;; ---------------------------------------------------------------------------

(deftest a-command-becomes-events-test
  (each-adapter
   (fn [app]
     (let [events (app/handle app truck-1 (command :load-truck {:flavour "vanilla" :quantity 3}))]
       (is (= [:truck-loaded] (map :event/type events)))
       (is (= [1] (map :stream/version events)))
       (is (= {"vanilla" 3} (app/stock app truck-1)))))))

(deftest selling-the-last-cone-is-two-facts-test
  (each-adapter
   (fn [app]
     (rostered app)
     (app/handle app truck-1 (command :load-truck {:flavour "vanilla" :quantity 1}))
     (let [events (app/handle app truck-1 (command dana :buy-flavour {:flavour "vanilla"}))]
       (is (= [:flavour-sold :stock-depleted] (map :event/type events)))
       (is (= {"vanilla" 0} (app/stock app truck-1)))))))

(deftest a-refused-command-records-nothing-test
  (each-adapter
   (fn [app]
     (rostered app)
     (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Sold out"
                           (app/handle app truck-1 (command dana :buy-flavour {:flavour "vanilla"}))))
     (is (= [:driver-assigned] (map :event/type (driven/read-stream (:store app) truck-1)))
         "the roster is there; the refused sale is not"))))

(deftest a-stale-version-is-refused-test
  (each-adapter
   (fn [app]
     (app/handle app truck-1 (command :load-truck {:flavour "vanilla" :quantity 2}))
     (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Concurrent modification"
          (driven/append (:store app) truck-1 0 (command :load-truck {})
                         [{:event/type :truck-loaded :event/id (random-uuid)
                           :event/occurred-at t0 :data {:flavour "vanilla" :quantity 1}}]))))))

(deftest only-a-depletion-is-announced-test
  (each-adapter
   (fn [app]
     (rostered app)
     (app/handle app truck-1 (command :load-truck {:flavour "vanilla" :quantity 5}))
     (app/handle app truck-1 (command dana :buy-flavour {:flavour "vanilla"}))
     (is (empty? (driven/pending (:outbox app))) "an ordinary sale interests nobody")
     (dotimes [_ 4] (app/handle app truck-1 (command dana :buy-flavour {:flavour "vanilla"})))
     (is (= 2 (count (driven/pending (:outbox app)))) "the depletion reaches two modules"))))

(deftest the-policy-closes-the-loop-test
  (each-adapter
   (fn [app]
     (rostered app)
     (app/handle app truck-1 (command :load-truck {:flavour "vanilla" :quantity 1}))
     (app/handle app truck-1 (command dana :buy-flavour {:flavour "vanilla"}))
     (let [{:keys [commands]} (app/react app 0 truck-1)]
       (is (= [:load-truck] (map :command/type commands)))
       (is (= {"vanilla" 20} (app/stock app truck-1)))))))

(deftest nothing-new-means-nothing-happens-test
  (each-adapter
   (fn [app]
     (app/handle app truck-1 (command :load-truck {:flavour "vanilla" :quantity 2}))
     (let [{:keys [checkpoint]} (app/react app 0 truck-1)
           quiet (app/react app checkpoint truck-1)]
       (is (empty? (:commands quiet)))))))

;; ---------------------------------------------------------------------------
;; Every fact remembers who is answerable for it
;; ---------------------------------------------------------------------------

(deftest the-actor-is-recorded-in-metadata-test
  (each-adapter
   (fn [app]
     (rostered app)
     (app/handle app truck-1 (command :load-truck {:flavour "vanilla" :quantity 1}))
     (app/handle app truck-1 (command dana :buy-flavour {:flavour "vanilla"}))
     (let [actors (map (comp :actor :metadata) (driven/read-stream (:store app) truck-1))]
       (is (= [{:type "user" :id "USR-11902"}      ; Rudi rostered
               {:type "user" :id "USR-11902"}      ; Rudi loaded
               {:type "user" :id "USR-83721"}      ; Dana sold
               {:type "user" :id "USR-83721"}]     ; and depleted it
              actors)
           "an opaque id per fact, and a different one where the actor differs")))))

(deftest a-recorded-actor-survives-a-json-round-trip-test
  (testing "the property, asserted once, instead of a coercion rule per field"
    ;; Labs 19, 22 and 24 each fixed one field that JSON had damaged. This
    ;; asserts the thing those fixes were reaching for: what goes into a stream
    ;; comes back identical, with nothing in between to put it right.
    ;;
    ;; It fails in memory as loudly as against Postgres, so the next keyword
    ;; anybody adds to metadata is caught without Docker and without a fourth
    ;; investigation.
    (each-adapter
     (fn [app]
       (rostered app)
       (app/handle app truck-1 (command :load-truck {:flavour "vanilla" :quantity 1}))
       (doseq [event (driven/read-stream (:store app) truck-1)]
         (let [actor (get-in event [:metadata :actor])]
           (is (= actor (json/read-str (json/write-str actor) :key-fn keyword))
               "a keyword here would not come back as one")))))))

;; ---------------------------------------------------------------------------
;; The core is testable without any of this
;; ---------------------------------------------------------------------------

(deftest the-core-needs-no-system-at-all-test
  (testing "no adapter, no component, no fixture, no token — just values"
    (is (= [{:event/type :flavour-sold :data {:flavour "vanilla"}}
            {:event/type :stock-depleted :data {:flavour "vanilla"}}]
           (truck/decide (command dana :buy-flavour {:flavour "vanilla"})
                         {:stock {"vanilla" 1} :driver "USR-83721"})))
    (is (= "customer-app ← flavour-unavailable (vanilla)"
           (contract/describe
            (first (contract/announce {:event/type :stock-depleted
                                       :event/id (random-uuid)
                                       :stream/id truck-1
                                       :data {:flavour "vanilla"}})))))))
