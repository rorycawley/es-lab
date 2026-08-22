(ns lab28.system
  "One deployment, two modules, two database identities, one telemetry pipeline.

  This composition root may name both public module APIs. Neither module may
  name the other's implementation or database. Catalog and Ordering connect
  as different Postgres roles, so this is enforced below the source tree.

  It is also the only namespace besides `platform/telemetry.clj` allowed to
  name OpenTelemetry, and it names the half nothing else should: *where the
  telemetry goes*. Lab 23 confined reitit to a driving adapter and this file;
  the same rule applies to an SDK whose whole job is to be configured once, at
  the outside edge, by whoever is deploying.

  Lab 28 makes it the only place that names **Stripe and SendGrid**. Payments
  is handed something that satisfies `payments.port/PaymentGateway` and never
  asks what it is. Swapping either provider is an edit to this file and a new
  adapter -- which is the claim, and `gateway_contract_test.clj` is the
  evidence, because the same suite passes against two implementations of each."
  (:require [lab28.catalog.api :as catalog]
            [lab28.catalog.contract :as catalog-contract]
            [lab28.http :as http]
            [lab28.notifications.adapter.memory :as memory-emailer]
            [lab28.notifications.adapter.sendgrid :as sendgrid]
            [lab28.notifications.api :as notifications]
            [lab28.ordering.api :as ordering]
            [lab28.ordering.contract :as ordering-contract]
            [lab28.payments.adapter.memory :as memory-gateway]
            [lab28.payments.adapter.stripe :as stripe]
            [lab28.payments.api :as payments]
            [lab28.payments.contract :as payments-contract]
            [lab28.platform.bus :as bus]
            [next.jdbc :as jdbc]
            [ring.adapter.jetty :as jetty]
            [steffan-westcott.clj-otel.sdk.otel-sdk :as sdk])
  (:import (io.opentelemetry.instrumentation.logback.appender.v1_0 OpenTelemetryAppender)))

(defn start-telemetry!
  "Configure the SDK and bridge the logging library into it.

  `exporters` is `{:spans [...] :logs [...] :metrics [...]}` — supplied by the
  caller, because which collector receives this is a deployment decision and
  not one the application gets to hold an opinion about. `dev/recorder.clj`
  passes in-memory ones; a deployment would pass OTLP.

  Order matters. `OpenTelemetryAppender/install` is what makes an ordinary
  `log/info` become an OpenTelemetry log record stamped with its trace, and
  until it runs, log statements go to the console and nowhere else."
  [{:keys [spans logs metrics]}]
  (let [otel (sdk/init-otel-sdk!
              "lab28"
              (cond-> {}
                (seq spans)   (assoc :tracer-provider
                                     {:span-processors [{:exporters spans :batch? false}]})
                (seq logs)    (assoc :logger-provider
                                     {:log-record-processors [{:exporters logs :batch? false}]})
                (seq metrics) (assoc :meter-provider
                                     {:readers (mapv #(hash-map :metric-reader %) metrics)})))]
    (OpenTelemetryAppender/install otel)
    otel))

(defn stop-telemetry! []
  (sdk/close-otel-sdk!))

(defn payment-gateway
  "Choose a gateway. The only function in the repository that may.

  `:memory` is not a lesser option kept for tests. It is the second
  implementation that makes the port a port, and the contract suite holds both
  to the same promises."
  [{:keys [provider base-url api-key instance] :or {provider :memory}}]
  (case provider
    :stripe (stripe/gateway {:base-url base-url :api-key api-key})
    :memory (memory-gateway/gateway)
    ;; A gateway built by the caller. A deployment might do this to wrap one in
    ;; a circuit breaker; `idempotency_test.clj` does it to wrap one in a
    ;; crash.
    :given  instance))

(defn emailer
  [{:keys [provider base-url api-key instance] :or {provider :memory}}]
  (case provider
    :sendgrid (sendgrid/emailer {:base-url base-url :api-key api-key})
    :memory   (memory-emailer/emailer)
    :given    instance))

(defn start
  ([config] (start config {}))
  ([{catalog-config      :catalog
     ordering-config     :ordering
     payments-config     :payments
     notifications-config :notifications
     gateway-config      :gateway
     emailer-config      :emailer}
    {:keys [new-id subscribe?] :or {new-id random-uuid subscribe? true}}]
   (let [messages  (bus/bus)
         gateway   (payment-gateway (or gateway-config {}))
         mailer    (emailer (or emailer-config {}))
         orders    (ordering/new-module (jdbc/get-datasource ordering-config)
                                        {:new-id new-id})
         catalogue (catalog/new-module (jdbc/get-datasource catalog-config)
                                       {:new-id new-id})
         money     (when payments-config
                     (payments/new-module (jdbc/get-datasource payments-config)
                                          gateway {:new-id new-id}))
         post      (when notifications-config
                     (notifications/new-module (jdbc/get-datasource notifications-config)
                                               mailer {:new-id new-id}))]
     ;; `:subscribe? false` leaves the bus with no subscribers, which is what a
     ;; deployment looks like while a consumer is down or not yet rolled out.
     ;; The relays still publish, the outbox still marks, and nothing is
     ;; delivered -- a state worth being able to reproduce on purpose.
     (when subscribe?
       (bus/subscribe! messages catalog-contract/price-changed-type
                       #(ordering/receive! orders %))
       (when money
         (bus/subscribe! messages ordering-contract/order-placed-type
                         #(payments/charge! money %)))
       (when post
         (bus/subscribe! messages payments-contract/payment-succeeded-type
                         #(notifications/receive! post %))))
     (cond-> {:catalog  catalogue
              :ordering orders
              :bus      messages
              :gateway  gateway
              :emailer  mailer}
       money (assoc :payments money)
       post  (assoc :notifications post)))))

(defn relay-catalog!
  [{:keys [catalog bus]}]
  (catalog/relay! catalog #(bus/publish! bus %)))

(defn relay-ordering!
  [{:keys [ordering bus]}]
  (ordering/relay! ordering #(bus/publish! bus %)))

(defn relay-payments!
  [{:keys [payments bus]}]
  (when payments (payments/relay! payments #(bus/publish! bus %))))

(defn relay-all!
  "Drain every module's outbox, in the order the conversation flows.

  A real deployment runs one relay per module, independently and repeatedly.
  Draining them in sequence here makes a test deterministic without pretending
  the modules share a transaction -- each publish is still its own."
  [app]
  {:catalog  (count (:published (relay-catalog! app)))
   :ordering (count (:published (relay-ordering! app)))
   :payments (count (:published (or (relay-payments! app) {})))})

(defn handler
  "The inbound HTTP edge. A function from a map to a map, so most of the
  webhook suite needs no socket at all."
  [app {:keys [signing-secret now tolerance-seconds]}]
  (http/router (cond-> {:payments (:payments app)
                        :signing-secret signing-secret
                        :now now}
                 tolerance-seconds (assoc :tolerance-seconds tolerance-seconds))))

(defn serve!
  "Start Jetty. One test proves there is a socket; everything else does not
  need one."
  [app {:keys [port] :or {port 3000} :as options}]
  (jetty/run-jetty (handler app options) {:port port :join? false}))
