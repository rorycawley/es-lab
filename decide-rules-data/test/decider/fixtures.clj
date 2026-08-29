(ns decider.fixtures
  "The example catalog used by tests and the development playground.

   It deliberately lives on test paths: applications supply their own reviewed
   bundles, and depending on the library must not add these examples to their
   runtime classpath."
  (:require
   [decider.bundle :as bundle]))

(def resource-directory
  "Where example bundles live on the test classpath."
  "semantic-bundles")

(def resource-paths
  "Every example bundle exercised by the generic and generative suites."
  ["semantic-bundles/ebay-place-bid.edn"
   "semantic-bundles/airline-reserve-seat.edn"
   "semantic-bundles/ticketmaster-reserve-tickets.edn"
   "semantic-bundles/amazon-add-item.edn"
   "semantic-bundles/land-registry-register-transfer.edn"
   "semantic-bundles/property-bidding-place-bid.edn"
   "semantic-bundles/secret-santa-assign-recipient.edn"])

(defn load-all
  "Load every example bundle, in declared order."
  []
  (mapv bundle/load resource-paths))
