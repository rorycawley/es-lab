(ns registry.messaging.topology
  (:require [langohr.exchange :as le]
            [langohr.queue    :as lq]))

(def exchange-name "registry.events")

(def routing-keys
  {:registration-application-created   "registration.application.created"
   :registration-application-submitted "registration.application.submitted"
   :examination-started                "registration.application.examination-started"
   :registration-application-approved  "registration.application.approved"
   :registration-application-rejected  "registration.application.rejected"
   :registration-application-withdrawn "registration.application.withdrawn"
   :company-registered                 "registration.company.registered"})

(defn routing-key-for [event-type]
  (get routing-keys event-type (str "registration.unknown." (name event-type))))

(def queues
  {:examiner-work-queue {:name    "registry.examiner.work-queue"
                         :binding "registration.application.submitted"}
   :register-projector  {:name    "registry.register.projector"
                         :binding "registration.company.registered"}
   :audit-queue         {:name    "registry.audit.queue"
                         :binding "#"}})

(defn declare-topology! [ch]
  (le/declare ch exchange-name "topic" {:durable true :auto-delete false})
  (doseq [{:keys [name binding]} (vals queues)]
    (lq/declare ch name {:durable true :auto-delete false})
    (lq/bind    ch name exchange-name {:routing-key binding})))
