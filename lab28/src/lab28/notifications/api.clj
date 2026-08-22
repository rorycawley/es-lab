(ns lab28.notifications.api
  "Notifications' public module API."
  (:require [lab28.notifications.get-notification :as get-notification]
            [lab28.notifications.port :as port]
            [lab28.notifications.send-receipt :as send-receipt]
            [lab28.platform.behaviour :as behaviour]))

(defrecord Notifications [receive get-notification provider audit-log])

(defn new-module
  ([datasource emailer] (new-module datasource emailer {}))
  ([datasource emailer {:keys [new-id] :or {new-id random-uuid}}]
   (let [audit   (atom [])
         context {:datasource datasource :emailer emailer :new-id new-id}
         receive (behaviour/compose
                  #(send-receipt/handle! context %)
                  [(behaviour/telemetry
                    :notifications/send-receipt
                    ;; The recipient is not an attribute. Lab 26 spent a suite
                    ;; keeping that address out of telemetry, and a module
                    ;; whose entire job is emailing people is the easiest place
                    ;; to let it back in.
                    {:kind       :consumer
                     :parent     :headers
                     :attributes (fn [{:keys [message]}]
                                   {:fact-id (get-in message [:payload :fact-id])})})
                   (behaviour/observation audit :notifications/send-receipt)
                   (behaviour/validation send-receipt/Request)])
         query   (behaviour/compose
                  #(get-notification/handle context %)
                  [(behaviour/telemetry
                    :notifications/get-notification
                    {:attributes #(select-keys % [:fact-id])})
                   (behaviour/observation audit :notifications/get-notification)
                   (behaviour/validation get-notification/Request)])]
     (->Notifications receive query (port/provider-name emailer) #(deref audit)))))

(defn receive! [notifications delivery] ((:receive notifications) delivery))
(defn get-notification [notifications request] ((:get-notification notifications) request))
(defn provider [notifications] (:provider notifications))
(defn audit-log [notifications] ((:audit-log notifications)))
