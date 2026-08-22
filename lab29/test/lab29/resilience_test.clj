(ns lab29.resilience-test
  "The fallacies, one mechanism at a time.

  Each test names a thing that is true inside a process and false across a
  network, and asserts what the system does about it."
  (:require [clojure.test :refer [deftest is testing]]
            [lab29.fake-sendgrid :as fake-sendgrid]
            [lab29.fake-stripe :as fake-stripe]
            [lab29.notifications.adapter.sendgrid :as sendgrid]
            [lab29.notifications.port :as email-port]
            [lab29.payments.adapter.stripe :as stripe]
            [lab29.payments.port :as pay-port]
            [lab29.platform.resilience :as resilience]))

(defn- charge [payment-id]
  {:payment-id payment-id :amount-cents 600 :currency "eur"
   :instrument "pm_card_visa" :description "resilience"})

(defn- message []
  {:notification-id (random-uuid) :to "ada@example.test"
   :subject "s" :body "b"})

;; ---------------------------------------------------------------------------
;; "The network is reliable"
;; ---------------------------------------------------------------------------

(deftest a-provider-having-a-bad-moment-is-not-a-failed-payment-test
  (let [provider (fake-stripe/start!)]
    (try
      (let [gateway (stripe/gateway {:base-url (:base-url provider)
                                     :api-key "sk_test_lab29"})]
        (fake-stripe/fail-times! provider 2)
        (is (= :authorized (:outcome (pay-port/authorize! gateway (charge (random-uuid)))))
            "two 503s and an answer, from one call the caller made once")
        (is (= 3 (count (fake-stripe/charges provider)))
            "three requests went out")
        (is (= 1 (count (fake-stripe/intents provider)))
            "and one payment exists, because every attempt carried the same key"))
      (finally (fake-stripe/stop! provider)))))

(deftest retrying-stops-rather-than-hanging-forever-test
  ;; A retry is a bet that the problem is brief. When it is not, the caller
  ;; needs the answer more than it needs another attempt.
  (let [provider (fake-stripe/start!)]
    (try
      (let [gateway (stripe/gateway {:base-url (:base-url provider)
                                     :api-key "sk_test_lab29"})]
        (fake-stripe/fail-times! provider 99)
        (is (= :provider-unavailable
               (:reason (ex-data (try (pay-port/authorize! gateway (charge (random-uuid)))
                                      (catch clojure.lang.ExceptionInfo e e))))))
        (is (= 4 (count (fake-stripe/charges provider)))
            "the initial attempt plus three retries, and then it stopped"))
      (finally (fake-stripe/stop! provider)))))

;; ---------------------------------------------------------------------------
;; "Latency is zero"
;; ---------------------------------------------------------------------------

(deftest a-deadline-bounds-the-whole-call-not-each-attempt-test
  ;; Without this, four attempts at ten seconds each is a forty-second request
  ;; and a caller that has already given up.
  (let [attempts (atom 0)
        policy   (resilience/policy {:name :test/slow
                                     :retry-reasons #{:provider-unreachable}
                                     :max-retries 100
                                     :backoff-ms [20 60 1.5]
                                     :max-duration-ms 200})
        started  (System/currentTimeMillis)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (resilience/call! policy
                                   (fn []
                                     (swap! attempts inc)
                                     (throw (ex-info "no" {:reason :provider-unreachable}))))))
    (let [elapsed (- (System/currentTimeMillis) started)]
      (is (< elapsed 2000) (str "gave up after " elapsed "ms, not after 100 attempts"))
      (is (< @attempts 100)
          (str "stopped at " @attempts " attempts because the budget ran out")))))

;; ---------------------------------------------------------------------------
;; "Topology doesn't change" — and the harm retries can do
;; ---------------------------------------------------------------------------

(deftest a-breaker-stops-us-making-somebody-elses-outage-worse-test
  ;; Every request to a struggling provider is one their recovery has to
  ;; survive. The breaker is the difference between retrying and piling on.
  (let [provider (fake-sendgrid/start!)]
    (try
      (let [emailer (sendgrid/emailer {:base-url (:base-url provider) :api-key "SG.lab29"})]
        (fake-sendgrid/fail-times! provider 999 429)
        (testing "enough failures across enough calls, and it opens"
          (dotimes [_ 3]
            (try (email-port/send! emailer (message)) (catch Exception _ nil)))
          (let [requests-before (count (fake-sendgrid/sent provider))
                failure (try (email-port/send! emailer (message))
                             (catch clojure.lang.ExceptionInfo e e))]
            (is (= :provider-circuit-open (:reason (ex-data failure)))
                "the call fails without being made")
            (is (= requests-before (count (fake-sendgrid/sent provider)))
                "and the provider was left alone")))

        (testing "the failure is a domain failure, not a library's class"
          (let [failure (try (email-port/send! emailer (message))
                             (catch clojure.lang.ExceptionInfo e e))]
            (is (= :notifications/sendgrid (:provider (ex-data failure)))))))
      (finally (fake-sendgrid/stop! provider)))))

(deftest a-breaker-lets-the-provider-back-in-test
  ;; Open forever is just a slower outage. After its delay it lets one probe
  ;; through, and a provider that has recovered gets its traffic back.
  (let [provider (fake-sendgrid/start!)]
    (try
      (let [emailer (sendgrid/emailer {:base-url (:base-url provider) :api-key "SG.lab29"
                                       :retry {:breaker (resilience/breaker
                                                         {:failure-threshold-ratio [4 6]
                                                          :success-threshold 1
                                                          :delay-ms 150})}})]
        (fake-sendgrid/fail-times! provider 999 429)
        (dotimes [_ 2]
          (try (email-port/send! emailer (message)) (catch Exception _ nil)))
        (is (= :provider-circuit-open
               (:reason (ex-data (try (email-port/send! emailer (message))
                                      (catch clojure.lang.ExceptionInfo e e))))))

        (fake-sendgrid/fail-times! provider 0 nil)
        (Thread/sleep 250)
        (is (= :sent (:outcome (email-port/send! emailer (message))))
            "half-open let a probe through, and it worked"))
      (finally (fake-sendgrid/stop! provider)))))

;; ---------------------------------------------------------------------------
;; The rule that governs all of it
;; ---------------------------------------------------------------------------

(deftest what-may-be-retried-is-decided-by-the-provider-not-by-us-test
  ;; The two adapters are written with equal care and have different policies,
  ;; because they are talking to providers offering different guarantees. This
  ;; is the whole lab, expressed as two sets.
  (testing "Stripe: every failure, because an idempotency key makes it safe"
    (is (contains? stripe/retry-reasons :provider-unreachable)))
  (testing "SendGrid: only the failure that proves nothing happened"
    (is (contains? sendgrid/retry-reasons :provider-rate-limited))
    (is (not (contains? sendgrid/retry-reasons :provider-unreachable))
        "a timeout might mean the mail was accepted and the answer lost, and
         there is no key that would make asking again safe")))

(deftest an-unreachable-emailer-is-not-retried-test
  ;; The consequence, on the wire: one attempt, not four.
  (let [emailer (sendgrid/emailer {:base-url "http://localhost:1" :api-key "SG.lab29"})
        started (System/currentTimeMillis)
        failure (try (email-port/send! emailer (message))
                     (catch clojure.lang.ExceptionInfo e e))]
    (is (= :provider-unreachable (:reason (ex-data failure))))
    (is (< (- (System/currentTimeMillis) started) 2000)
        "it returned immediately rather than backing off three times")))
