(ns lab26.demo
  (:gen-class)
  (:require [clojure.string :as str]
            [lab26.catalog.api :as catalog]
            [lab26.ordering.api :as ordering]
            [lab26.postgres :as postgres]
            [lab26.recorder :as recorder]
            [lab26.system :as system]))

(def vanilla #uuid "0f1c2b3a-0000-4000-8000-000000000026")
(def pistachio #uuid "0f1c2b3a-0000-4000-8000-000000000027")
(def customer "ada@example.com")
(def rule "  ──────────────────────────────────────────────────────────────")

(defn- price-request [price-cents]
  {:command-id (random-uuid)
   :correlation-id (random-uuid)
   :product-id vanilla
   :product-name "vanilla"
   :price-cents price-cents})

(defn- place-order! [ordering product-id quantity]
  (ordering/place-order! ordering {:order-id (random-uuid)
                                   :correlation-id (random-uuid)
                                   :product-id product-id
                                   :quantity quantity
                                   :customer-email customer}))

;; ---------------------------------------------------------------------------
;; Rendering
;; ---------------------------------------------------------------------------

(defn- attributes-of [{:keys [attributes]}]
  (str/join " " (for [[k v] (sort attributes)
                      :when (str/starts-with? k "es.")
                      :when (not (#{"es.outcome" "es.module" "es.request"} k))]
                  (str (str/replace k "es." "") "=" v))))

(defn- render-span [spans logs indent {:keys [name span-id status events attributes]}]
  (println (format "  %s%-34s %s"
                   indent name
                   (if (= :ERROR status)
                     (str "ERROR " (str/join "," events))
                     (get attributes "es.outcome" "-"))))
  (doseq [log (filter #(= span-id (:span-id %)) logs)]
    (println (format "  %s  log  %s" indent (attributes-of log))))
  (doseq [child (filter #(= span-id (:parent-id %)) spans)]
    (render-span spans logs (str indent "  ") child)))

(defn- render-trace! []
  (let [spans (recorder/recorded-spans)
        logs  (recorder/recorded-logs)]
    (doseq [root (filter (comp nil? :parent-id) spans)]
      (println (str "  trace " (:trace-id root)))
      (render-span spans logs "" root)
      (println))))

(defn- render-statuses! []
  (doseq [{:keys [name status events attributes]} (recorder/recorded-spans)]
    (println (format "    %-34s status=%-6s outcome=%s%s"
                     name (clojure.core/name status)
                     (get attributes "es.outcome" "-")
                     (if (seq events) (str "   recorded: " (str/join "," events)) "")))))

;; ---------------------------------------------------------------------------

(defn- colliding-ids
  "An id generator that hands out one outbox message id twice, so the second
  price change violates a unique constraint and the whole command fails."
  []
  (let [message-id (random-uuid)
        remaining  (atom [(random-uuid) message-id (random-uuid) message-id])]
    (fn [] (let [[id & more] @remaining] (reset! remaining more) id))))

(defn -main [& _]
  (recorder/start!)
  (postgres/truncate!)
  (let [{:keys [catalog ordering] :as app} (system/start (postgres/config))]

    (println)
    (println "  One trace. Two modules. Two transactions. Nothing shared.")
    (println rule)
    (println)
    (recorder/clear!)
    (catalog/change-price! catalog (price-request 300))
    (system/relay-catalog! app)
    (render-trace!)
    (println "  The publish and the copy belong to the price change because its")
    (println "  traceparent was written into the outbox row, inside the very")
    (println "  transaction that changed the price.")
    (println)

    (println rule)
    (println)
    (println "  A refusal is not an error.")
    (println)
    (recorder/clear!)
    (catalog/change-price! catalog (price-request 0))
    (place-order! ordering pistachio 2)
    (render-statuses!)
    (println)
    (println "  Nobody is paged because a client sent a zero price, or because")
    (println "  a product has no local price yet. Both of those are answers.")
    (println)

    (println rule)
    (println)
    (println "  A machine failure is.")
    (println)
    (recorder/clear!)
    (let [{failing :catalog} (system/start (postgres/config) {:new-id (colliding-ids)})]
      (catalog/change-price! failing (price-request 300))
      (try
        (catalog/change-price! failing (price-request 450))
        (catch Exception _ nil)))
    (render-statuses!)
    (println)
    (println "  Same span name, same code path, a different signal — and the")
    (println "  exception is recorded on the span rather than left in a log")
    (println "  file for somebody to correlate by timestamp.")
    (println)

    (println rule)
    (println)
    (println "  What the customer's email did not reach.")
    (println)
    (system/relay-catalog! app)
    (recorder/clear!)
    (place-order! ordering vanilla 2)
    (let [emitted (concat (mapcat (comp vals :attributes) (recorder/recorded-spans))
                          (mapcat (comp vals :attributes) (recorder/recorded-logs))
                          (keep :body (recorder/recorded-logs)))]
      (println "    values handed to the telemetry pipeline:" (count emitted))
      (println (str "    of those containing " customer ":  ")
               (count (filter #(str/includes? (str %) customer) emitted)))
      (println "    rows in ordering.orders holding it:      1"))
    (println)
    (println "  Lab 15 can erase a fact from an append-only store. It cannot")
    (println "  reach a copy sitting inside somebody else's retention window.")
    (println rule)
    (println))

  (system/stop-telemetry!)
  (shutdown-agents))
