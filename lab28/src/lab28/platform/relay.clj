(ns lab28.platform.relay
  "Draining an outbox, including the messages that will not go.

  A relay's happy path is three lines. Everything here is the other path, and
  the reason it is worth a namespace is that a naive relay has two failure
  modes and both are quiet:

  **It stops.** One message the consumer cannot accept sits at the head of the
  queue and everything behind it waits, indefinitely, while the outbox depth
  graph climbs and nothing alerts because nothing errored.

  **Or it spins.** The relay retries the same poison message forever, at
  whatever rate the loop allows, which is a denial-of-service attack on your
  own consumer.

  The fix for both is to give up deliberately: count the attempts, and when a
  message has failed enough times to look permanent rather than unlucky, move
  it somewhere an operator can find it and carry on with the rest.

  The module supplies the SQL, because the module owns its tables (lab 25).
  This supplies the policy, because the policy should be the same everywhere."
  (:require [lab28.platform.telemetry :as telemetry]))

(def attempts-before-death
  "Three deliveries, then the graveyard.

  Low on purpose. This count is not a retry budget -- the adapters already
  retry inside a single attempt, with backoff -- it is how many *separate
  relay passes* a message gets before we conclude the problem is the message
  rather than the moment."
  3)

(defn drain!
  "Publish every pending message. Dead-letter the ones that keep refusing.

  `ports` supplies the module's own SQL as functions:

  | `:pending`         | messages waiting, oldest first |
  | `:mark-published!` | this one went                  |
  | `:record-failure!` | this one did not, and why      |
  | `:dead-letter!`    | this one never will            |

  A failure is caught rather than propagated, so one undeliverable message
  does not stop the ones behind it. The exception is not swallowed: it is
  written to the row, then to the graveyard, and the span carries it."
  [{:keys [pending mark-published! record-failure! dead-letter! publish!]}]
  (reduce
   (fn [summary {:keys [headers message attempts] :as delivery}]
     (let [message-id (:message/id message)
           ;; The span is named after the message rather than after this
           ;; namespace, so a trace still reads
           ;; `catalog publish-price-changed` and an operator can tell one
           ;; relay from another.
           span-name  (keyword (namespace (:message/type message))
                               (str "publish-" (name (:message/type message))))]
       (try
         (let [published (telemetry/observe
                          {:name       span-name
                           :kind       :producer
                           :parent     headers
                           :attributes {:message-id message-id
                                        :fact-id (get-in message [:payload :fact-id])
                                        :attempts attempts}}
                          (fn []
                            (let [outgoing {:headers (telemetry/trace-headers)
                                            :message message}]
                              {:completed (publish! outgoing)
                               :headers   (:headers outgoing)})))]
           (mark-published! message-id)
           (update summary :published conj
                   {:message message :headers (:headers published)
                    :published (:completed published)}))
         (catch Throwable t
           (let [attempted (inc (or attempts 0))
                 reason    (or (:reason (ex-data t)) :delivery-failed)
                 detail    (str (name reason) ": " (ex-message t))]
             (if (< attempted attempts-before-death)
               (do (record-failure! message-id detail)
                   (update summary :failed conj {:message-id message-id
                                                 :attempts attempted
                                                 :because detail}))
               (do (dead-letter! delivery attempted detail)
                   (update summary :dead-lettered conj
                           {:message-id message-id
                            :attempts attempted
                            :because detail}))))))))
   {:published [] :failed [] :dead-lettered []}
   (pending)))
