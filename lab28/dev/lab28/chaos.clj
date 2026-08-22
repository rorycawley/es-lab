(ns lab28.chaos
  "Adapters that fail where failing is most inconvenient.

  Retry logic is written for failures nobody arranges, and then tested against
  failures everybody arranges: a decline, a 500, a timeout. The failure that
  actually costs money is the one in the middle -- the remote side succeeded
  and the local side never found out -- and it cannot be produced by asking a
  provider to misbehave. It has to be injected here."
  (:require [lab28.notifications.port :as email-port]
            [lab28.payments.port :as pay-port]))

(defrecord CrashAfterAuthorize [gateway crashed?]
  pay-port/PaymentGateway
  (provider-name [_] (pay-port/provider-name gateway))
  (authorize! [_ charge]
    (let [answer (pay-port/authorize! gateway charge)]
      (if @crashed?
        answer
        (do (reset! crashed? true)
            ;; The money moved. This process will never know it.
            (throw (ex-info "the lights went out"
                            {:reason :crashed-after-remote-effect})))))))

(defn crash-after-authorize
  "Delegates, then dies -- once. The second call behaves normally, which is
  what a restarted process looks like."
  [gateway]
  (->CrashAfterAuthorize gateway (atom false)))

(defrecord CrashAfterSend [emailer crashed?]
  email-port/Emailer
  (provider-name [_] (email-port/provider-name emailer))
  (send! [_ message]
    (let [answer (email-port/send! emailer message)]
      (if @crashed?
        answer
        (do (reset! crashed? true)
            (throw (ex-info "the lights went out"
                            {:reason :crashed-after-remote-effect})))))))

(defn crash-after-send [emailer] (->CrashAfterSend emailer (atom false)))
