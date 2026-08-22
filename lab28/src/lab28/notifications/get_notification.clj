(ns lab28.notifications.get-notification
  "The complete `Get notification` query slice.

  `attempts` is in the response because it is the honest answer to \"did the
  customer get one email or two?\", and hiding it would make the module look
  more reliable than the provider allows it to be."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def Request
  [:map {:closed true}
   [:fact-id :uuid]])

(defn handle
  [{:keys [datasource]} {:keys [fact-id]}]
  (if-let [row (jdbc/execute-one!
                datasource
                ["SELECT notification_id, fact_id, subject, status,
                         provider_reference, attempts, failure_reason
                    FROM notifications.notification WHERE fact_id = ?"
                 fact-id]
                {:builder-fn rs/as-unqualified-kebab-maps})]
    {:found row}
    {:not-found fact-id}))
