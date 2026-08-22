(ns lab29.notifications.adapter.sendgrid
  "The SendGrid adapter, and its anticorruption layer.

  Same two halves as the Stripe adapter: `translate-*` is pure and does the
  vocabulary work, the rest is transport. The interesting difference is what
  the translation has to *admit*.

  SendGrid answers `202 Accepted` and an `X-Message-Id`. Accepted is not sent,
  and sent is not delivered -- delivery is reported later, by a webhook, or
  never. So `:sent` here means *the provider took responsibility for it*, which
  is the strongest thing this integration can honestly claim, and the port says
  so."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [lab29.notifications.port :as port]
            [lab29.platform.resilience :as resilience])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.time Duration)))

;; ---------------------------------------------------------------------------
;; The anticorruption layer
;; ---------------------------------------------------------------------------

(defn ->mail-send
  "Our message, in SendGrid's shape.

  `custom_args` carries our notification id outward. It is not an idempotency
  key -- SendGrid has none -- but it is what makes a duplicate identifiable
  after the fact, in their logs and in any event webhook. Recognising a
  duplicate is not preventing one, and this lab is careful not to confuse the
  two."
  [{:keys [notification-id to subject body]}]
  {"personalizations" [{"to" [{"email" to}]
                        "custom_args" {"notification_id" (str notification-id)}}]
   "from"    {"email" "receipts@example.test" "name" "Ice Cream Truck"}
   "subject" subject
   "content" [{"type" "text/plain" "value" body}]})

(defn translate-response
  "SendGrid's answer, as our domain sees it."
  [{:keys [status headers body]}]
  (cond
    (= 202 status)
    {:outcome :sent :reference (get headers "x-message-id")}

    ;; 400 is SendGrid saying no to this particular message -- a malformed
    ;; address, a suppressed recipient. An answer about the message.
    (= 400 status)
    {:outcome :rejected
     :because (or (some-> (json/read-str (or body "{}"))
                          (get "errors") first (get "message"))
                  "rejected")}

    ;; 429 and 5xx are SendGrid failing to answer. Not a rejection: the message
    ;; may still be sendable a moment from now, and treating it as rejected
    ;; would lose a receipt the customer is owed.
    :else
    (throw (ex-info "SendGrid did not answer"
                    {:reason (if (= 429 status) :provider-rate-limited :provider-unavailable)
                     :provider :sendgrid
                     :status status}))))

;; ---------------------------------------------------------------------------
;; Transport
;; ---------------------------------------------------------------------------

(def retry-reasons
  "One reason, and the shortness of this set is the whole point.

  A rate limit is the only failure here that *proves nothing happened*:
  SendGrid rejected the request before doing anything with it, so asking again
  costs nothing but patience.

  `:provider-unreachable` is deliberately absent. A read timeout means the
  request may have been accepted and the answer lost, and there is no
  idempotency key to make a second attempt safe -- so retrying it is a coin
  flip on whether the customer gets two receipts. Compare
  `payments.adapter.stripe/retry-reasons`, which can afford to retry
  everything, and note that the difference is not care taken but a header the
  provider offers."
  #{:provider-rate-limited})

(defrecord SendGridEmailer [client base-url api-key policy]
  port/Emailer
  (provider-name [_] "sendgrid")

  (send! [_ message]
    (resilience/call!
     policy
     (fn []
       (let [request  (-> (HttpRequest/newBuilder
                           (URI/create (str base-url "/v3/mail/send")))
                          (.timeout (Duration/ofSeconds 10))
                          (.header "Content-Type" "application/json")
                          (.header "Authorization" (str "Bearer " api-key))
                          (.POST (HttpRequest$BodyPublishers/ofString
                                  (json/write-str (->mail-send message))))
                          (.build))
             response (try
                        (.send client request (HttpResponse$BodyHandlers/ofString))
                        (catch java.io.IOException e
                          (throw (ex-info "Could not reach SendGrid"
                                          {:reason :provider-unreachable
                                           :provider :sendgrid}
                                          e)))
                        (catch InterruptedException e
                          (throw (ex-info "Interrupted while calling SendGrid"
                                          {:reason :provider-unreachable
                                           :provider :sendgrid}
                                          e))))]
         (translate-response
          {:status  (.statusCode response)
           :headers (into {} (map (fn [[k v]] [(str/lower-case k) (first v)]))
                          (.map (.headers response)))
           :body    (.body response)}))))))

(defn emailer
  [{:keys [base-url api-key retry]}]
  (->SendGridEmailer (-> (HttpClient/newBuilder)
                         (.connectTimeout (Duration/ofSeconds 5))
                         (.build))
                     base-url
                     api-key
                     (resilience/policy
                      (merge {:name          :notifications/sendgrid
                              :retry-reasons retry-reasons
                              :breaker       (resilience/breaker
                                              ;; See the note in the Stripe
                                              ;; adapter: wider than one call's
                                              ;; retries, so a single bad
                                              ;; request is not an outage.
                                              {:failure-threshold-ratio [8 12]
                                               :success-threshold       2
                                               :delay-ms                200})}
                             retry))))
