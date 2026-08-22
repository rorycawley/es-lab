(ns lab29.websub.adapter
  "The consumer that turns internal facts into a public resource.

  It is a *policy* in the messaging document's sense, and the contrast with
  `ordering.fulfilment` is the clearest one this lab has. Both react to facts.
  Neither is a substitute for the other:

  | | `ordering.fulfilment` | this |
  |---|---|---|
  | remembers | where the order's conversation got to | nothing |
  | cares about order | yes -- a payment before a placement is refused | no |
  | owns | a sequence | a projection |

  A policy that needed to remember would be a process manager wearing the
  wrong name, and a process manager that did not would be a policy with a
  table. The test for which one you have is whether the reaction is decidable
  from the triggering fact alone.

  It is also, deliberately, the only thing in this lab that knows both a
  business contract and WebSub. No module names this namespace."
  (:require [lab29.catalog.contract :as catalog-contract]
            [lab29.websub.hub :as hub]
            [lab29.websub.topics :as topics]
            [next.jdbc :as jdbc]))

(def contract
  "WebSub's contract, and it is a consumer only.

  It handles no commands and publishes no integration events, because what it
  produces is not an internal message at all -- it is a change to a public web
  resource. That asymmetry is why it is an adapter with a schema rather than a
  business module: it consumes the inside and writes to the outside."
  {:module           :websub
   :handles-commands #{}
   :consumes-events  #{:catalog/price-changed :catalog/product-described}
   :publishes-events #{}
   :provides-queries #{}})

(def Request
  [:or catalog-contract/PriceChanged catalog-contract/ProductDescribed])

(defn- claim!
  [tx {:keys [fact-id message-id correlation-id]}]
  (jdbc/execute-one!
   tx
   ["INSERT INTO websub.inbox (fact_id, first_message_id, correlation_id)
     VALUES (?, ?, ?) ON CONFLICT (fact_id) DO NOTHING RETURNING fact_id"
    fact-id message-id correlation-id]))

(defn handle!
  "Fold one fact into the public resource, then tell the hub the topic moved.

  The projection update is transactional; the push to subscribers is not, and
  cannot be -- they are on the internet. A subscriber that misses the push and
  re-fetches the topic is still correct, which is the property that makes that
  acceptable, and the property a log-shaped integration would not have."
  [{:keys [datasource hub base-url]} {:keys [message]}]
  (let [payload (:payload message)
        {:keys [correlation-id]} (:metadata message)
        applied (jdbc/with-transaction [tx datasource]
                  (when (claim! tx {:fact-id (:fact-id payload)
                                    :message-id (:message/id message)
                                    :correlation-id correlation-id})
                    (case (:event/type message)
                      :catalog/price-changed     (topics/apply-price-changed! tx payload)
                      :catalog/product-described (topics/apply-product-described! tx payload))
                    true))]
    (if-not applied
      {:duplicate (:fact-id payload)}
      (let [topic     (topics/topic-url base-url (:product-id payload))
            body      (topics/body (topics/representation datasource (:product-id payload)))
            delivered (hub/distribute! hub topic body)]
        {:accepted {:topic topic
                    :subscribers (count delivered)
                    :delivered (count (filter :delivered delivered))}}))))
