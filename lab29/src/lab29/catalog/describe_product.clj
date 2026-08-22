(ns lab29.catalog.describe-product
  "The complete `Describe product` command slice.

  It exists because search needs something to search. A price is a number and
  a number has no lexemes, so until a product has prose, full-text search has
  nothing to do.

  New in lab 29: it publishes. Lab 28 deliberately kept this command silent,
  because Ordering wants prices and does not care about prose. That stopped
  being the whole story when a second consumer appeared: the public product
  resource has a description on it, and a description that only refreshes when
  somebody happens to change a price is a stale public API.

  Which consumer wants a fact is not the publisher's business to guess -- the
  question is whether the fact is one the module is prepared to expose."
  (:require [lab29.catalog.contract :as contract]
            [lab29.catalog.outbox :as outbox]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def Request
  [:map {:closed true}
   [:command-id :uuid]
   [:correlation-id :uuid]
   [:product-id :uuid]
   [:description [:string {:min 1 :max 2000}]]])

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn- prior-result
  [db {:keys [command-id product-id description]}]
  (when-let [row (jdbc/execute-one!
                  db
                  ["SELECT product_id, description
                      FROM catalog.description_ledger
                     WHERE command_id = ?"
                   command-id]
                  opts)]
    (if (= (select-keys row [:product-id :description])
           {:product-id product-id :description description})
      {:accepted row}
      (throw (ex-info "Command id already identifies another description"
                      {:reason :command-id-collision
                       :command-id command-id})))))

(defn handle!
  [{:keys [datasource new-id]} {:keys [command-id correlation-id product-id description]
                                :as request}]
  (or (prior-result datasource request)
      (jdbc/with-transaction [tx datasource]
        ;; The update comes first so that a command about a product Catalog has
        ;; never priced claims nothing. Both statements still commit together,
        ;; which is the only ordering guarantee that matters -- but a ledger row
        ;; recording a command that changed nothing would make a later retry
        ;; report success it never had.
        (if-let [updated (jdbc/execute-one!
                          tx
                          ["UPDATE catalog.product SET description = ?
                             WHERE product_id = ?
                         RETURNING product_id, description"
                           description product-id]
                          opts)]
          (if (jdbc/execute-one!
               tx
               ["INSERT INTO catalog.description_ledger
                   (command_id, correlation_id, product_id, description)
                 VALUES (?, ?, ?, ?)
                 ON CONFLICT (command_id) DO NOTHING
                 RETURNING command_id"
                command-id correlation-id product-id description])
            (do (outbox/enqueue! tx (contract/product-described
                                     (new-id) (new-id) command-id correlation-id
                                     product-id description))
                {:accepted updated})
            (prior-result tx request))
          {:not-found product-id}))))
