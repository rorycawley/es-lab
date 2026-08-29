(ns decider.bundle
  "Resource loading and semantic-bundle construction.

   The primary I/O edge in the project. `load` is named for what it does: it
   crosses a data-scope boundary and actually loads a resource. Domain rules do
   not belong here."
  (:refer-clojure :exclude [load])
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [decider.core :as core]
   [decider.hash :as hash]
   [decider.schema :as schema])
  (:import
   [java.io PushbackReader]))

(def resource-directory
  "Where the bundles live on the classpath."
  "semantic-bundles")

(def resource-paths
  "The bundles `load-all` reads, and therefore the bundles the generative suite
   exercises.

   Written out rather than discovered, because a classpath directory can only be
   listed when it is a directory — from inside a jar it cannot be — and a
   function whose result depends on how the project was packaged is worse than a
   list. `decider.bundle-test` asserts this matches the contents of
   `resources/semantic-bundles` exactly, in both directions, so a bundle added
   without being listed fails the build instead of silently going untested."
  ["semantic-bundles/ebay-place-bid.edn"
   "semantic-bundles/airline-reserve-seat.edn"
   "semantic-bundles/ticketmaster-reserve-tickets.edn"
   "semantic-bundles/amazon-add-item.edn"
   "semantic-bundles/land-registry-register-transfer.edn"
   "semantic-bundles/property-bidding-place-bid.edn"
   "semantic-bundles/secret-santa-assign-recipient.edn"])

(defn- read-specification
  "Parse the single EDN form at `resource-path`. No validation, no hash.

   `edn/read-string` was the obvious thing here and the wrong one: it returns
   the *first* form and discards the rest of the file silently, so a bundle
   accidentally holding two maps — a bad merge, a stray paste — would load as
   whichever came first and nothing would say so. Reading form by form and
   insisting on exactly one turns that into an error."
  [resource-path]
  (let [resource (or (io/resource resource-path)
                     (throw
                      (ex-info "Semantic bundle resource not found"
                               {:resource-path resource-path})))
        eof (Object.)]
    (with-open [reader (PushbackReader. (io/reader resource))]
      (let [form  (edn/read {:eof eof} reader)
            extra (edn/read {:eof eof} reader)]
        (cond
          (identical? eof form)
          (throw (ex-info "Semantic bundle resource is empty"
                          {:resource-path resource-path}))

          (not (identical? eof extra))
          (throw (ex-info "Semantic bundle resource contains more than one form"
                          {:resource-path resource-path}))

          :else form)))))

(defn load
  "Read the semantic bundle at `resource-path` from the classpath.

   Validates it, then attaches its content hash. Throws if the resource is
   missing, empty, holds more than one form, or is not an executable bundle.

   Returns the specification as data — useful for reading and comparing.
   `load-prepared` is what to call when the intent is to decide with it."
  [resource-path]
  (let [specification (schema/assert-valid-bundle! (read-specification resource-path))]
    (assoc specification :spec/hash (hash/specification-hash specification))))

(defn load-prepared
  "Read the bundle at `resource-path` and prepare it, in one step.

   The same result as `(decider.core/prepare (load resource-path))`, but
   validated once instead of twice: `load` validates what it read, and `prepare`
   validates what it was given, which is the same bundle checked twice on this
   path. Each is right to check on its own — `prepare` also accepts hand-built
   specifications — so the duplication is removed by skipping the intermediate
   rather than by weakening either."
  [resource-path]
  (core/prepare (read-specification resource-path)))

(defn load-all
  "Every bundle in `resource-paths`, in order, as data."
  []
  (mapv load resource-paths))
