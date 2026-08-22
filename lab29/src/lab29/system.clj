(ns lab29.system
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
  (:require [lab29.catalog.api :as catalog]
            [lab29.http :as http]
            [lab29.notifications.adapter.memory :as memory-emailer]
            [lab29.notifications.adapter.sendgrid :as sendgrid]
            [lab29.notifications.api :as notifications]
            [lab29.ordering.api :as ordering]
            [lab29.payments.adapter.memory :as memory-gateway]
            [lab29.payments.adapter.stripe :as stripe]
            [lab29.payments.api :as payments]
            [lab29.platform.dispatcher :as dispatcher]
            [next.jdbc :as jdbc]
            [lab29.websub.adapter :as websub-adapter]
            [lab29.websub.hub :as websub-hub]
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
              "lab29"
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
  ([{catalog-config       :catalog
     ordering-config      :ordering
     payments-config      :payments
     notifications-config :notifications
     websub-config        :websub
     gateway-config       :gateway
     emailer-config       :emailer
     base-url             :base-url
     :or                  {base-url "http://localhost:3000"}}
    {:keys [new-id clock handlers subscribe?]
     :or   {new-id random-uuid subscribe? true}}]
   (let [gateway   (payment-gateway (or gateway-config {}))
         mailer    (emailer (or emailer-config {}))
         orders    (ordering/new-module (jdbc/get-datasource ordering-config)
                                        {:new-id new-id})
         catalogue (catalog/new-module (jdbc/get-datasource catalog-config)
                                       {:new-id new-id})
         money     (payments/new-module (jdbc/get-datasource payments-config)
                                        gateway {:new-id new-id})
         post      (notifications/new-module (jdbc/get-datasource notifications-config)
                                             mailer {:new-id new-id})
         hub       (websub-hub/hub (cond-> {:datasource (jdbc/get-datasource websub-config)
                                            :hub-url (str base-url "/hub")
                                            :new-id new-id}
                                     clock (assoc :clock clock)))
         websub    {:datasource (jdbc/get-datasource websub-config)
                    :hub hub
                    :base-url base-url}
         ;; The routing table is derived from what the modules declare, not
         ;; written here. A command with two handlers or an event nobody
         ;; publishes stops the system starting, which is the only moment
         ;; anyone is definitely looking.
         messages  (dispatcher/dispatcher
                    [catalog/contract ordering/contract payments/contract
                     notifications/contract websub-adapter/contract]
                    ;; `handlers` lets a caller substitute one consumer. That is
                    ;; not a testing hook bolted on: a consumer is chosen by the
                    ;; composition root exactly like a payment provider is, and
                    ;; being able to point one at something else is the same
                    ;; capability lab 21 gave the driven ports.
                    (merge (if subscribe?
                             {:catalog       #(throw (ex-info "Catalog consumes nothing"
                                                              {:reason :unwired-consumer}))
                              :ordering      #(ordering/receive! orders %)
                              :payments      #(payments/charge! money %)
                              :notifications #(notifications/receive! post %)
                              :websub        #(websub-adapter/handle! websub %)}
                             ;; Capture mode still derives destinations from
                             ;; contracts, but deliberately performs no
                             ;; consumer side effects. The relay summary then
                             ;; exposes the deliveries for controlled replay.
                             (zipmap [:catalog :ordering :payments :notifications :websub]
                                     (repeat (constantly {:accepted :captured}))))
                           handlers))]
     {:catalog       catalogue
      :ordering      orders
      :payments      money
      :notifications post
      :websub        websub
      :websub-hub    hub
      :dispatcher    messages
      :gateway       gateway
      :emailer       mailer
      :base-url      base-url})))

(defn relay-catalog!  [{:keys [catalog dispatcher]}]  (catalog/relay! catalog dispatcher))
(defn relay-ordering! [{:keys [ordering dispatcher]}] (ordering/relay! ordering dispatcher))
(defn relay-payments! [{:keys [payments dispatcher]}] (payments/relay! payments dispatcher))

(defn relay-all!
  "Drain every module's outbox, in the order the conversation flows.

  A real deployment runs one relay per module, independently and repeatedly.
  Draining them in sequence here makes a test deterministic without pretending
  the modules share a transaction -- each delivery is still its own."
  [app]
  {:catalog  (count (:delivered (relay-catalog! app)))
   :ordering (count (:delivered (relay-ordering! app)))
   :payments (count (:delivered (relay-payments! app)))})

(defn handler
  "The inbound HTTP edge. A function from a map to a map, so most of the
  webhook suite needs no socket at all."
  [app {:keys [signing-secret now tolerance-seconds]}]
  (http/router (cond-> {:payments (:payments app)
                        :websub (:websub app)
                        :websub-hub (:websub-hub app)
                        :hub-url (str (:base-url app) "/hub")
                        :signing-secret signing-secret
                        :now now}
                 tolerance-seconds (assoc :tolerance-seconds tolerance-seconds))))

(defn serve!
  "Start Jetty. One test proves there is a socket; everything else does not
  need one."
  [app {:keys [port] :or {port 3000} :as options}]
  (jetty/run-jetty (handler app options) {:port port :join? false}))
