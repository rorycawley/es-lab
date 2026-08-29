(ns semantic-core.demo
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [semantic-core.engine :as e]
            [semantic-core.operators :refer [operators]]))

(defn load-bundle [name]
  (-> (str "bundles/" name ".edn") io/resource slurp edn/read-string))

(defn run-case [bundle-name command prior-events workflow-message]
  (let [bundle (load-bundle bundle-name)
        state (e/hydrate operators bundle prior-events)
        decision (e/decide operators bundle command state)
        new-state (reduce #(e/evolve operators bundle %1 %2) state (:events decision))
        reaction (when workflow-message
                   (e/react operators bundle workflow-message (get-in bundle [:workflow :initial])))]
    {:bundle bundle-name
     :before state
     :decision decision
     :after new-state
     :reaction reaction}))

(def cases
  [{:bundle-name "ticket"
    :prior-events [{:event/type :ticket/seat-held :data {:seat "A-10" :customer "C-1"}}]
    :command {:command/type :ticket/confirm-sale :data {:seat "A-10" :customer "C-1"}}
    :workflow-message {:event/type :payment/confirmed :data {:seat "A-10" :customer "C-1"}}}

   {:bundle-name "land"
    :prior-events [{:event/type :land/title-created :data {:title-id "T-1" :proprietor "P-1"}}]
    :command {:command/type :land/register-transfer :data {:title-id "T-1" :from "P-1" :to "P-2"}}
    :workflow-message {:event/type :land/transfer-approved :data {:title-id "T-1" :from "P-1" :new-proprietor "P-2"}}}

   {:bundle-name "corporate"
    :prior-events [{:event/type :company/application-submitted :data {:application-id "A-1"}}
                   {:event/type :company/payment-noted :data {:application-id "A-1"}}
                   {:event/type :company/first-examination-completed :data {:examiner-id "E-1"}}]
    :command {:command/type :company/approve-application :data {:application-id "A-1" :examiner-id "E-2"}}
    :workflow-message {:event/type :company/application-approved :data {:application-id "A-1"}}}])

(defn execute [{:keys [bundle-name command prior-events workflow-message]}]
  (run-case bundle-name command prior-events workflow-message))

(defn -main [& _]
  (doseq [case cases]
    (let [result (execute case)]
      (println "\n===" (:bundle result) "===")
      (prn result))))
