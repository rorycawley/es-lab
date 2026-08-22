(ns lab28.platform.resilience
  "The one namespace that knows how a call to somebody else is protected.

  Peter Deutsch's fallacies of distributed computing are a list of things that
  are true inside a process and false across a network. Three of them have
  mechanisms here:

  | the network is reliable | retry, because a failure is often a hiccup |
  | latency is zero         | a deadline budget, because slow is a failure |
  | topology doesn't change | a circuit breaker, because the far end can go |

  The fourth mechanism is the one people forget, and it is the reason this
  namespace exists at all: **knowing when to stop.** Retrying a provider that
  is down converts their outage into yours, because every request you make is
  a request their recovery has to survive. The breaker is what stops help
  becoming harm.

  ## Retrying is not free, and not always allowed

  A retry is only safe if the call is idempotent. This lab has both cases and
  they get different policies, which is why `retry-when` takes a set of reasons
  rather than a boolean: an adapter has to say *which failures it knows changed
  nothing*, and only those may be repeated.

  diehard and `dev.failsafe` stop here. `architecture_test.clj` fails the build
  if either name appears anywhere else, and `CircuitBreakerOpenException` is
  translated into an ordinary domain failure before it leaves."
  (:require [diehard.circuit-breaker :as dh-cb]
            [diehard.core :as dh])
  (:import (dev.failsafe CircuitBreakerOpenException)))

(def defaults
  "Deliberately modest, and stated rather than buried.

  Four attempts over at most five seconds. Real numbers belong in
  configuration and come from what the provider's own latency looks like at
  the 99th percentile, not from a blog post."
  {:max-retries     3
   :backoff-ms      [50 500 2.0]
   :jitter-factor   0.3
   :max-duration-ms 5000})

(defn retry-when
  "Retry only failures whose `:reason` says nothing happened.

  The predicate reads ex-data rather than exception classes on purpose. What
  matters is not what went wrong but whether the far side acted, and only the
  adapter that spoke to it can say."
  [reasons]
  (fn [_result ^Throwable exception]
    (boolean (and exception (contains? reasons (:reason (ex-data exception)))))))

(defn breaker
  "A circuit breaker. Shared by every call through one policy, because its
  whole job is to notice a pattern across calls.

  `:failure-threshold-ratio [n m]` opens after n failures in the last m
  attempts. `:delay-ms` is how long it stays open before letting one probe
  through; `:success-threshold` is how many probes must pass to close it."
  [options]
  (dh-cb/circuit-breaker options))

(defn policy
  "Build a call policy. `:name` is for telemetry and error messages only."
  [{:keys [name retry-reasons breaker] :as options}]
  {:name    name
   :diehard (cond-> (merge defaults
                           (select-keys options [:max-retries :backoff-ms
                                                 :jitter-factor :max-duration-ms]))
              retry-reasons (assoc :retry-if (retry-when retry-reasons))
              breaker       (assoc :circuit-breaker breaker))})

(defn call!
  "Run `thunk` under `policy`, and return what it returns.

  A breaker that is open fails immediately, and does so as an ordinary domain
  failure: nothing outside this namespace should have to catch a class from a
  resilience library to find out that a provider is unavailable."
  [{:keys [name diehard]} thunk]
  (try
    (dh/with-retry diehard (thunk))
    (catch CircuitBreakerOpenException _
      (throw (ex-info "Refusing to call a provider that is failing"
                      {:reason   :provider-circuit-open
                       :provider name})))))

(defn state
  "`:closed`, `:open` or `:half-open`, for an operator-facing health endpoint."
  [breaker]
  (dh-cb/state breaker))
