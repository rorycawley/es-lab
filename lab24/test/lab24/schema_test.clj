(ns lab24.schema-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [lab24.core.truck :as truck]
            [lab24.schema.command :as command]
            [lab24.schema.event :as event]
            [malli.core :as m]))

(def dana {:type "user" :id "USR-83721"})

;; A truck with vanilla on it and Dana driving. `decide` now asks two
;; questions, so the fixture has to answer both.
(defn- stocked [n] {:stock {"vanilla" n} :driver "USR-83721"})

(defn- buy [flavour]
  {:command/id (random-uuid) :command/type :buy-flavour :command/actor dana
   :data {:flavour flavour}})

(defn- load-truck [flavour quantity]
  {:command/id (random-uuid) :command/type :load-truck :command/actor dana
   :data {:flavour flavour :quantity quantity}})

;; ---------------------------------------------------------------------------
;; The reason the lab exists
;; ---------------------------------------------------------------------------

(deftest validation-passing-is-not-permission-test
  (testing "a perfectly well-formed command the domain will refuse"
    (let [cmd         (buy "vanilla")
          empty-truck (stocked 0)]
      (is (nil? (command/validate cmd))
          "well-formed: a uuid, a known type, a known flavour, a known actor")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Sold out"
                            (truck/decide cmd empty-truck))
          "and correctly refused, because there is no vanilla")))
  (testing "both answers are right — they are answers to different questions"
    (let [cmd (buy "vanilla")]
      (is (nil? (command/validate cmd)))
      (is (= [{:event/type :flavour-sold :data {:flavour "vanilla"}}]
             (truck/decide cmd (stocked 5)))
          "the same command, the same schema, a different state, a different outcome")))
  (testing "and a third answer the schema equally cannot give"
    ;; Authorisation joins the list of things a well-formed command can still
    ;; fail. Same shape, same validity, and the truck says no because of who
    ;; is asking rather than what is on board.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Not this truck's driver"
                          (truck/decide (buy "vanilla")
                                        {:stock {"vanilla" 5} :driver "USR-55010"})))))

(deftest a-schema-cannot-see-state-test
  (testing "the schema's verdict does not change when the world does"
    (let [cmd (buy "vanilla")]
      (is (= (command/validate cmd) (command/validate cmd)))
      (testing "while decide's does"
        (is (thrown? clojure.lang.ExceptionInfo (truck/decide cmd (stocked 0))))
        (is (some? (truck/decide cmd (stocked 1))))))))

;; ---------------------------------------------------------------------------
;; Closed on the way in
;; ---------------------------------------------------------------------------

(deftest a-well-formed-command-passes-test
  (is (nil? (command/validate (buy "chocolate"))))
  (is (nil? (command/validate (load-truck "vanilla" 12)))))

(deftest malformed-commands-are-explained-not-just-rejected-test
  (testing "a flavour the truck has never heard of"
    (is (some? (command/validate (buy "tarmac")))))
  (testing "a quantity outside the allowed range"
    (is (some? (command/validate (load-truck "vanilla" 0)))))
  (testing "a missing field"
    (is (some? (command/validate {:command/id (random-uuid)
                                  :command/type :buy-flavour
                                  :data {}}))))
  (testing "an id that is not an id"
    (is (some? (command/validate (assoc (buy "vanilla") :command/id "nope"))))))

(deftest an-unexpected-key-is-refused-at-the-door-test
  (testing "closed, because a surprise from outside is a bug or an attack"
    (is (some? (command/validate (assoc (buy "vanilla") :admin? true))))
    (is (some? (command/validate (assoc-in (buy "vanilla") [:data :discount] 0.5))))))

(deftest the-actor-is-part-of-the-shape-test
  (testing "a command with no actor is not a command"
    (is (some? (command/validate (dissoc (buy "vanilla") :command/actor)))))
  (testing "and an actor has a kind, which is a closed list"
    (is (some? (command/validate (assoc (buy "vanilla") :command/actor {:type "user"}))))
    (is (some? (command/validate (assoc (buy "vanilla")
                                        :command/actor {:type "hacker" :id "x"})))))
  (testing "a system is a first-class kind of actor, because a policy is not a person"
    (is (nil? (command/validate (assoc (load-truck "vanilla" 3)
                                       :command/actor {:type   "system"
                                                       :id     "restock-when-depleted"}))))))

(deftest an-unknown-command-type-is-refused-test
  (is (some? (command/validate {:command/id (random-uuid)
                                :command/type :steal-truck
                                :data {}}))))

;; ---------------------------------------------------------------------------
;; Open on the way out
;; ---------------------------------------------------------------------------

(deftest an-event-with-a-field-we-have-never-seen-still-reads-test
  (testing "lab 13's argument, as a setting rather than a principle"
    (is (event/valid-data? :flavour-sold {:flavour "vanilla" :loyalty-card "C-9"})
        "a field added after this code was deployed")
    (is (= {:flavour "vanilla" :loyalty-card "C-9"}
           (event/decode-data :flavour-sold {:flavour "vanilla" :loyalty-card "C-9"}))
        "and it survives decoding untouched")))

(deftest closing-the-event-schemas-would-recreate-lab13s-failure-test
  (testing "the same data against a closed version of the same schema"
    (let [closed [:map {:closed true} [:flavour [:enum "vanilla"]]]]
      (is (not (m/validate closed {:flavour "vanilla" :loyalty-card "C-9"}))
          "a reader that crashes on its own history")
      (is (m/validate closed {:flavour "vanilla"})))))

(deftest an-event-type-this-code-has-never-heard-of-passes-through-test
  (testing "same reason: the stream outlives the reader"
    (is (event/valid-data? :truck-repainted {:colour "pink"}))
    (is (= {:colour "pink"} (event/decode-data :truck-repainted {:colour "pink"})))))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")

;; ---------------------------------------------------------------------------
;; Decoding, and which losses are worth it
;; ---------------------------------------------------------------------------

(deftest the-loss-worth-decoding-is-the-one-you-cannot-avoid-test
  (testing "JSON has no UUID type, and no design decision changes that"
    (is (= {:flavour "vanilla" :quantity 20 :truck-id truck-1}
           (event/decode-data :truck-loaded {:flavour "vanilla" :quantity 20
                                             :truck-id (str truck-1)}))
        "a policy stamps :truck-id (lab 10); the store hands it back as a string")))

(deftest the-loss-not-worth-decoding-is-the-one-you-declined-to-have-test
  (testing "nothing else here needs a decoder, because nothing else was lost"
    (let [written {:flavour "vanilla" :quantity 20}]
      (is (= written (event/decode-data :truck-loaded written))
          "the flavour was a string going in and is a string coming back")
      (testing "and it survives its own encoding with no schema involved at all"
        (is (= written (json/read-str (json/write-str written) :key-fn keyword)))))))

(deftest a-keyword-value-would-not-survive-which-is-why-there-are-none-test
  (testing "the loss labs 19 and 22 kept curing, shown rather than patched"
    ;; `:key-fn keyword` restores keys, because their names are known in
    ;; advance. There is no equivalent for values, and there cannot be: by the
    ;; time you are decoding, a string is all there is.
    (is (= {:flavour "vanilla"}
           (json/read-str (json/write-str {:flavour :vanilla}) :key-fn keyword))
        "a keyword goes in and a string comes out, silently and one way")))

(deftest decoding-is-driven-by-the-declaration-not-a-list-test
  (testing "lab 19 kept #{:flavour :reason :reason-code} up to date by hand"
    (is (contains? event/by-type :truck-loaded)
        "adding an event type adds its coercion, because they are the same statement")))

(deftest decoding-an-already-decoded-value-is-harmless-test
  (is (= {:flavour "vanilla" :truck-id truck-1}
         (event/decode-data :truck-loaded {:flavour "vanilla" :truck-id truck-1}))))
