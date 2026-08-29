(ns consumer-smoke
  "An isolated application proving the supported dependency and resource boundary."
  (:require
   [clojure.java.io :as io]
   [decider.bundle :as bundle]
   [decider.core :as decider]
   [decider.schema :as schema]
   [malli.core :as m]))

(defn- check!
  [description predicate]
  (when-not predicate
    (throw (ex-info (str "Consumer smoke test failed: " description)
                    {:check description}))))

(defn -main
  "Exercise the supported API and fail fast when any consumer contract drifts."
  [& _]
  (let [prepared (bundle/load-prepared "consumer/semantic-bundle.edn")
        state {:resource-id "resource-1" :remaining 5}
        command {:command/type :reserve-capacity :data {:quantity 2}}
        accepted (decider/decide prepared state command)
        rejected (decider/decide prepared
                                 state
                                 (assoc-in command [:data :quantity] 6))
        invalid (decider/decide prepared
                                state
                                (assoc-in command [:data :quantity] "two"))]
    (check! "consumer-owned bundle is prepared"
            (decider/prepared? prepared))
    (check! "accepted result conforms to the public result schema"
            (m/validate schema/Result accepted))
    (check! "accepted decision contains the rendered consumer event"
            (= [{:event/type :capacity-reserved
                 :data {:resource-id "resource-1" :quantity 2}}]
               (get-in accepted [:decision :events])))
    (check! "business rejection preserves the rule contract"
            (= [:BR-2 :insufficient-capacity]
               [(get-in rejected [:decision :rule/id])
                (get-in rejected [:decision :reason])]))
    (check! "malformed input remains distinct from business rejection"
            (= :invalid-command (:result/type invalid)))
    (check! "example bundles are absent from the production dependency"
            (nil? (io/resource
                   "semantic-bundles/ticketmaster-reserve-tickets.edn")))
    (println "Consumer smoke test passed")))
