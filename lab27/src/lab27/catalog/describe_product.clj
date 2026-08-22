(ns lab27.catalog.describe-product
  "The complete `Describe product` command slice.

  It exists because search needs something to search. A price is a number and
  a number has no lexemes, so until a product has prose, full-text search has
  nothing to do.

  Note what this command does *not* do: it writes no outbox message. Lab 25's
  outbox carries facts another module needs, and Ordering does not need
  descriptions -- it needs prices. Not every command publishes, and deciding
  which do is the contract decision, not a mechanical consequence of writing."
  (:require [next.jdbc :as jdbc]
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
  [{:keys [datasource]} {:keys [command-id correlation-id product-id description] :as request}]
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
            {:accepted updated}
            (prior-result tx request))
          {:not-found product-id}))))
