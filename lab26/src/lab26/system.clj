(ns lab26.system
  "One deployment, two modules, two database identities, one telemetry pipeline.

  This composition root may name both public module APIs. Neither module may
  name the other's implementation or database. Catalog and Ordering connect
  as different Postgres roles, so this is enforced below the source tree.

  It is also the only namespace besides `platform/telemetry.clj` allowed to
  name OpenTelemetry, and it names the half nothing else should: *where the
  telemetry goes*. Lab 23 confined reitit to a driving adapter and this file;
  the same rule applies to an SDK whose whole job is to be configured once, at
  the outside edge, by whoever is deploying."
  (:require [lab26.catalog.api :as catalog]
            [lab26.catalog.contract :as catalog-contract]
            [lab26.ordering.api :as ordering]
            [lab26.platform.bus :as bus]
            [next.jdbc :as jdbc]
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
              "lab26"
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

(defn start
  ([config] (start config {}))
  ([{catalog-config :catalog ordering-config :ordering} {:keys [new-id]
                                                         :or   {new-id random-uuid}}]
   (let [messages  (bus/bus)
         orders    (ordering/new-module (jdbc/get-datasource ordering-config))
         catalogue (catalog/new-module (jdbc/get-datasource catalog-config)
                                       {:new-id new-id})]
     (bus/subscribe! messages catalog-contract/price-changed-type
                     #(ordering/receive! orders %))
     {:catalog  catalogue
      :ordering orders
      :bus      messages})))

(defn relay-catalog!
  [{:keys [catalog bus]}]
  (catalog/relay! catalog #(bus/publish! bus %)))
