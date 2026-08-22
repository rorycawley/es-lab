(ns lab29.gateway-contract-test
  "One suite, run against every implementation of each port.

  This is lab 21's neutral contract suite, pointed at somebody else's system
  instead of at Postgres. It is the evidence for the claim that these are
  abstractions rather than Stripe and SendGrid wearing a hat: if the port is
  really about taking money, the same assertions hold for an atom and for an
  HTTP provider, and a third adapter is a new file rather than a new design.

  It is also where each port's *promise* is asserted rather than merely
  documented -- including the promise Notifications cannot make."
  (:require [clojure.test :refer [deftest is testing]]
            [lab29.fake-sendgrid :as fake-sendgrid]
            [lab29.fake-stripe :as fake-stripe]
            [lab29.notifications.adapter.memory :as memory-emailer]
            [lab29.notifications.adapter.sendgrid :as sendgrid]
            [lab29.notifications.port :as email-port]
            [lab29.payments.adapter.memory :as memory-gateway]
            [lab29.payments.adapter.stripe :as stripe]
            [lab29.payments.port :as pay-port]))

(defn- with-gateways
  "Calls `f` once per PaymentGateway implementation."
  [f]
  (f "memory" (memory-gateway/gateway))
  (let [provider (fake-stripe/start!)]
    (try
      (f "stripe" (stripe/gateway {:base-url (:base-url provider)
                                   :api-key "sk_test_lab29"}))
      (finally (fake-stripe/stop! provider)))))

(defn- with-emailers [f]
  (f "memory" (memory-emailer/emailer #{"nope@invalid.test"}))
  (let [provider (fake-sendgrid/start!)]
    (try
      (f "sendgrid" (sendgrid/emailer {:base-url (:base-url provider)
                                       :api-key "SG.lab29"}))
      (finally (fake-sendgrid/stop! provider)))))

(defn- charge [payment-id instrument]
  {:payment-id payment-id :amount-cents 600 :currency "eur"
   :instrument instrument :description "one contract test"})

;; ---------------------------------------------------------------------------
;; PaymentGateway
;; ---------------------------------------------------------------------------

(deftest every-gateway-authorizes-test
  (with-gateways
    (fn [name gateway]
      (testing name
        (let [{:keys [outcome reference]} (pay-port/authorize!
                                           gateway (charge (random-uuid) "pm_card_visa"))]
          (is (= :authorized outcome))
          (is (string? reference) "an opaque reference an operator can paste somewhere"))))))

(deftest every-gateway-declines-in-domain-words-test
  (with-gateways
    (fn [name gateway]
      (testing name
        (let [answer (pay-port/authorize!
                      gateway (charge (random-uuid) "pm_card_chargeDeclined"))]
          (is (= :declined (:outcome answer)))
          (is (= "card_declined" (:because answer))
              "a decline is an answer about money, not an exception"))))))

(deftest every-gateway-can-answer-not-yet-test
  ;; The third outcome, and the one most often left out of a test double.
  ;; A gateway that can only succeed or fail lets every test pass while the
  ;; deployed system meets a card in 3-D Secure and has nowhere to put it.
  (with-gateways
    (fn [name gateway]
      (testing name
        (let [answer (pay-port/authorize!
                      gateway (charge (random-uuid) "pm_card_authenticationRequired"))]
          (is (= :pending (:outcome answer)))
          (is (string? (:reference answer))
              "a reference even so, because the callback will name it"))))))

(deftest every-gateway-is-idempotent-on-our-payment-id-test
  ;; The port's central promise. Two calls, one charge, the same answer -- which
  ;; is what makes the gap in `charge_order.clj` survivable.
  (with-gateways
    (fn [name gateway]
      (testing name
        (let [payment-id (random-uuid)
              first-try  (pay-port/authorize! gateway (charge payment-id "pm_card_visa"))
              retry      (pay-port/authorize! gateway (charge payment-id "pm_card_visa"))]
          (is (= (:reference first-try) (:reference retry))
              "the same reference means the same charge, not a second one")
          (is (= (:outcome first-try) (:outcome retry))))))))

(deftest the-stripe-adapter-actually-sends-the-header-test
  ;; The assertion above would pass even if the adapter forgot the header and
  ;; the fake were lenient. This one looks at the wire.
  (let [provider (fake-stripe/start!)]
    (try
      (let [gateway    (stripe/gateway {:base-url (:base-url provider) :api-key "sk_test_lab29"})
            payment-id (random-uuid)]
        (pay-port/authorize! gateway (charge payment-id "pm_card_visa"))
        (pay-port/authorize! gateway (charge payment-id "pm_card_visa"))
        (is (= [(str payment-id) (str payment-id)]
               (mapv :idempotency-key (fake-stripe/charges provider)))
            "our payment id, chosen before the first call, on every call")
        (is (= 1 (count (fake-stripe/intents provider)))
            "two requests, one payment intent"))
      (finally (fake-stripe/stop! provider)))))

(deftest an-unreachable-gateway-throws-rather-than-declining-test
  ;; Silently turning "we could not ask" into "they said no" loses orders that
  ;; were perfectly payable.
  (let [gateway (stripe/gateway {:base-url "http://localhost:1" :api-key "sk_test_lab29"})]
    (is (thrown? Exception (pay-port/authorize! gateway (charge (random-uuid) "pm_card_visa"))))))

(deftest a-bad-credential-is-not-a-decline-test
  (let [provider (fake-stripe/start!)]
    (try
      (let [gateway (stripe/gateway {:base-url (:base-url provider) :api-key "wrong"})
            failure (try (pay-port/authorize! gateway (charge (random-uuid) "pm_card_visa"))
                         (catch clojure.lang.ExceptionInfo e e))]
        (is (= :provider-unavailable (:reason (ex-data failure)))))
      (finally (fake-stripe/stop! provider)))))

;; ---------------------------------------------------------------------------
;; Emailer
;; ---------------------------------------------------------------------------

(deftest every-emailer-sends-test
  (with-emailers
    (fn [name emailer]
      (testing name
        (let [{:keys [outcome reference]}
              (email-port/send! emailer {:notification-id (random-uuid)
                                         :to "ada@example.test"
                                         :subject "Your receipt"
                                         :body "Thank you."})]
          (is (= :sent outcome))
          (is (string? reference)))))))

(deftest every-emailer-rejects-in-domain-words-test
  (with-emailers
    (fn [name emailer]
      (testing name
        (let [answer (email-port/send! emailer {:notification-id (random-uuid)
                                                :to "nope@invalid.test"
                                                :subject "s" :body "b"})]
          (is (= :rejected (:outcome answer)))
          (is (string? (:because answer))))))))

(deftest no-emailer-is-idempotent-and-the-port-says-so-test
  ;; The mirror image of the payment gateway test, and the reason both are in
  ;; one file. Same notification id, twice: two emails, both times.
  (with-emailers
    (fn [name emailer]
      (testing name
        (let [notification-id (random-uuid)
              message {:notification-id notification-id :to "ada@example.test"
                       :subject "Your receipt" :body "Thank you."}
              first-try (email-port/send! emailer message)
              retry     (email-port/send! emailer message)]
          (is (= :sent (:outcome first-try)))
          (is (= :sent (:outcome retry)))
          (is (not= (:reference first-try) (:reference retry))
              "two references means two emails -- email providers have no
               idempotency key, and the port promises only at-least-once"))))))

(deftest the-sendgrid-adapter-really-sends-twice-test
  (let [provider (fake-sendgrid/start!)]
    (try
      (let [emailer (sendgrid/emailer {:base-url (:base-url provider) :api-key "SG.lab29"})
            notification-id (random-uuid)
            message {:notification-id notification-id :to "ada@example.test"
                     :subject "Your receipt" :body "Thank you."}]
        (email-port/send! emailer message)
        (email-port/send! emailer message)
        (is (= 2 (count (fake-sendgrid/sent provider)))
            "the customer received two receipts, and no amount of client code
             could have prevented it")
        (is (= [(str notification-id) (str notification-id)]
               (mapv :notification-id (fake-sendgrid/sent provider)))
            "but both carry our id, so the duplicate is at least identifiable"))
      (finally (fake-sendgrid/stop! provider)))))

(deftest a-passing-rate-limit-is-absorbed-test
  ;; Retrying a 429 is safe precisely because SendGrid did nothing with the
  ;; request. It is the only failure this adapter is allowed to repeat.
  (let [provider (fake-sendgrid/start!)]
    (try
      (let [emailer (sendgrid/emailer {:base-url (:base-url provider) :api-key "SG.lab29"})]
        (fake-sendgrid/fail-times! provider 2 429)
        (is (= :sent (:outcome (email-port/send!
                                emailer {:notification-id (random-uuid)
                                         :to "ada@example.test"
                                         :subject "s" :body "b"}))))
        (is (= 1 (count (fake-sendgrid/sent provider)))
            "three requests, one email -- the two refusals sent nothing"))
      (finally (fake-sendgrid/stop! provider)))))

(deftest a-rate-limit-that-does-not-pass-still-throws-test
  ;; Retry is a bet that the problem is brief. When it is not, the caller has
  ;; to hear about it rather than be waited on indefinitely.
  (let [provider (fake-sendgrid/start!)]
    (try
      (let [emailer (sendgrid/emailer {:base-url (:base-url provider) :api-key "SG.lab29"})]
        (fake-sendgrid/fail-times! provider 99 429)
        (is (= :provider-rate-limited
               (:reason (ex-data (try (email-port/send!
                                       emailer {:notification-id (random-uuid)
                                                :to "ada@example.test"
                                                :subject "s" :body "b"})
                                      (catch clojure.lang.ExceptionInfo e e)))))))
      (finally (fake-sendgrid/stop! provider)))))

(deftest both-ports-name-their-provider-for-telemetry-only-test
  (is (= "memory" (pay-port/provider-name (memory-gateway/gateway))))
  (is (= "memory" (email-port/provider-name (memory-emailer/emailer)))))
