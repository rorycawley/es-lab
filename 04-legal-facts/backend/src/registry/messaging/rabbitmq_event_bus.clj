(ns registry.messaging.rabbitmq-event-bus
  (:require [langohr.channel :as lch]
            [langohr.basic   :as lb]
            [jsonista.core   :as json]
            [registry.messaging.topology  :as topology]
            [registry.decider.runner      :as runner]))

(defn- kw->str [k]
  (if-let [ns (namespace k)]
    (str ns "/" (name k))
    (name k)))

(def ^:private mapper
  (json/object-mapper {:encode-key-fn kw->str :decode-key-fn keyword}))

(defn- serialise [event]
  (-> event
      (update :event/type name)
      (update :occurred-at str)
      (json/write-value-as-bytes mapper)))

(defn- publish-opts [event]
  {:persistent?  true
   :content-type "application/json"
   :message-id   (str (:event/id event))})

(defn make-rabbitmq-event-bus [conn]
  (reify runner/EventBus
    (publish-events! [_ events]
      (let [ch (lch/open conn)]
        (try
          (doseq [event events]
            (lb/publish ch
                        topology/exchange-name
                        (topology/routing-key-for (:event/type event))
                        (serialise event)
                        (publish-opts event)))
          (finally (lch/close ch)))))))

(defn publish-one! [conn event]
  (let [ch (lch/open conn)]
    (try
      (lb/publish ch
                  topology/exchange-name
                  (topology/routing-key-for (:event/type event))
                  (serialise event)
                  (publish-opts event))
      (finally (lch/close ch)))))

(defn deserialise [^bytes payload]
  (-> (json/read-value payload mapper)
      (update :event/type keyword)))
