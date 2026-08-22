(ns lab29.acl-test
  "The anticorruption layer, driven with values alone.

  This is the cheapest and most valuable suite in the lab. A mistranslation
  does not throw -- it produces a plausible domain value that is quietly wrong,
  and it does so in a code path that only runs when a real provider says
  something unusual. Which is exactly when you cannot afford it."
  (:require [clojure.test :refer [deftest is testing]]
            [lab29.notifications.adapter.sendgrid :as sendgrid]
            [lab29.payments.adapter.stripe :as stripe]))

;; ---------------------------------------------------------------------------
;; Stripe: payment intents
;; ---------------------------------------------------------------------------

(deftest stripes-vocabulary-stops-at-the-adapter-test
  (testing "the statuses that mean money moved"
    (is (= {:outcome :authorized :reference "pi_1"}
           (stripe/translate-intent {"id" "pi_1" "status" "succeeded"}))))

  (testing "the statuses that mean it did not, and why"
    (is (= {:outcome :declined :reference "pi_2" :because "card_declined"}
           (stripe/translate-intent
            {"id" "pi_2" "status" "requires_payment_method"
             "last_payment_error" {"code" "card_declined"}})))
    (is (= {:outcome :declined :reference "pi_3" :because "canceled"}
           (stripe/translate-intent {"id" "pi_3" "status" "canceled"}))))

  (testing "the statuses that mean not yet"
    (doseq [status ["processing" "requires_action" "requires_confirmation"
                    "requires_capture"]]
      (is (= :pending (:outcome (stripe/translate-intent {"id" "pi_4" "status" status})))
          status))))

(deftest an-unknown-status-is-not-a-successful-payment-test
  ;; The failure mode this prevents: a `case` with a default branch, a provider
  ;; adding a status in a minor release, and a payment nobody understands being
  ;; recorded as one that worked.
  (let [failure (try (stripe/translate-intent {"id" "pi_9" "status" "quantum_superposition"})
                     (catch clojure.lang.ExceptionInfo e e))]
    (is (= :untranslatable-provider-response (:reason (ex-data failure))))
    (is (= "quantum_superposition" (:status (ex-data failure))))))

(deftest the-adapter-does-not-read-back-what-we-sent-test
  ;; Amount and currency are absent from every translation above. We sent them.
  ;; Trusting a provider's echo of our own data is how a rounding difference or
  ;; a currency mix-up becomes authoritative.
  (is (= #{:outcome :reference}
         (set (keys (stripe/translate-intent
                     {"id" "pi_5" "status" "succeeded"
                      "amount" 999999 "currency" "xyz"}))))))

;; ---------------------------------------------------------------------------
;; Stripe: webhook events
;; ---------------------------------------------------------------------------

(deftest subscribed-event-types-become-domain-facts-test
  (is (= {:provider "stripe"
          :provider-event-id "evt_1"
          :event-type "payment_intent.succeeded"
          :kind :payment/settled
          :reference "pi_1"}
         (stripe/translate-event
          {"id" "evt_1" "type" "payment_intent.succeeded"
           "data" {"object" {"id" "pi_1"}}})))
  (is (= :payment/declined
         (:kind (stripe/translate-event
                 {"id" "evt_2" "type" "payment_intent.payment_failed"
                  "data" {"object" {"id" "pi_2"
                                    "last_payment_error" {"code" "expired_card"}}}})))))

(deftest an-unsubscribed-event-type-is-ignored-not-failed-test
  ;; Providers send dozens of types and retry anything non-2xx. Treating an
  ;; uninteresting type as an error builds a retry loop that never terminates.
  (is (= :ignored
         (:kind (stripe/translate-event
                 {"id" "evt_3" "type" "customer.subscription.trial_will_end"
                  "data" {"object" {"id" "sub_1"}}})))))

(deftest a-subscribed-type-we-cannot-read-is-a-failure-test
  ;; The distinction that matters: silence about things we never asked for,
  ;; noise about things we did ask for and cannot understand.
  (doseq [broken [{"id" "evt_4" "type" "payment_intent.succeeded" "data" {"object" {}}}
                  {"type" "payment_intent.succeeded" "data" {"object" {"id" "pi_1"}}}]]
    (is (= :untranslatable-provider-event
           (:reason (ex-data (try (stripe/translate-event broken)
                                  (catch clojure.lang.ExceptionInfo e e))))))))

;; ---------------------------------------------------------------------------
;; Stripe: signatures
;; ---------------------------------------------------------------------------

(deftest a-signature-proves-origin-and-recency-test
  (let [secret "whsec_test" body "{\"id\":\"evt_1\"}" now 1700000000]
    (testing "a signature we made ourselves verifies"
      (is (:valid? (stripe/verify-signature
                    secret (stripe/sign secret now body) body now 300))))

    (testing "a body changed after signing does not"
      (is (= :signature-mismatch
             (:because (stripe/verify-signature
                        secret (stripe/sign secret now body)
                        "{\"id\":\"evt_evil\"}" now 300)))))

    (testing "another secret does not"
      (is (= :signature-mismatch
             (:because (stripe/verify-signature
                        secret (stripe/sign "whsec_other" now body) body now 300)))))

    (testing "a valid signature replayed hours later does not"
      ;; Without this, a captured callback is reusable forever.
      (is (= :signature-too-old
             (:because (stripe/verify-signature
                        secret (stripe/sign secret now body) body (+ now 3600) 300)))))

    (testing "and neither does nonsense"
      (doseq [header [nil "" "garbage" "t=1700000000" "v1=abc"]]
        (is (= :malformed-signature
               (:because (stripe/verify-signature secret header body now 300)))
            (str header))))))

;; ---------------------------------------------------------------------------
;; SendGrid
;; ---------------------------------------------------------------------------

(deftest sendgrids-vocabulary-stops-at-its-adapter-test
  (testing "202 Accepted is the strongest thing they will say"
    (is (= {:outcome :sent :reference "sg_1"}
           (sendgrid/translate-response {:status 202 :headers {"x-message-id" "sg_1"}}))))

  (testing "400 is an answer about this message"
    (is (= {:outcome :rejected :because "Does not contain a valid address."}
           (sendgrid/translate-response
            {:status 400
             :body "{\"errors\":[{\"message\":\"Does not contain a valid address.\"}]}"}))))

  (testing "429 and 5xx are not answers at all"
    ;; Calling a rate limit a rejection loses a receipt the customer is owed.
    (is (= :provider-rate-limited
           (:reason (ex-data (try (sendgrid/translate-response {:status 429})
                                  (catch clojure.lang.ExceptionInfo e e))))))
    (is (= :provider-unavailable
           (:reason (ex-data (try (sendgrid/translate-response {:status 503})
                                  (catch clojure.lang.ExceptionInfo e e))))))))

(deftest our-identifier-travels-outward-test
  (let [notification-id (random-uuid)
        payload (sendgrid/->mail-send {:notification-id notification-id
                                       :to "ada@example.test"
                                       :subject "Your receipt"
                                       :body "Thank you."})]
    (is (= (str notification-id)
           (get-in payload ["personalizations" 0 "custom_args" "notification_id"]))
        "not an idempotency key -- SendGrid has none -- but the thing that makes
         a duplicate identifiable afterwards")
    (is (= "ada@example.test" (get-in payload ["personalizations" 0 "to" 0 "email"])))))
