(ns lab29.fixture
  (:require [lab29.fake-sendgrid :as fake-sendgrid]
            [lab29.fake-stripe :as fake-stripe]
            [lab29.postgres :as postgres]
            [lab29.recorder :as recorder]
            [lab29.system :as system]))

(defn idle
  "A consumer that accepts everything and does nothing.

  Substituted for a module whose real handler the test intends to call itself,
  so that draining the queue does not do the work first."
  [_]
  {:accepted true})

(defn capture
  "A consumer that records what it was handed instead of acting on it.

  Returns `[handler seen]`. Substituting it for a module lets a test take a
  delivery off the queue and then drive the real consumer by hand, as many
  times as it likes -- which is how you assert what happens on the second
  delivery without waiting for one."
  []
  (let [seen (atom [])]
    [(fn [delivery]
       (swap! seen conj (select-keys delivery [:headers :message]))
       {:accepted true})
     seen]))

(defn with-system
  "The whole system, with in-memory providers. Fast, no sockets."
  ([f] (with-system {} f))
  ([opts f]
   (recorder/start!)
   (postgres/truncate!)
   (recorder/clear!)
   (f (system/start (assoc (postgres/config)
                           :base-url "http://registry.test"
                           :gateway {:provider :memory}
                           :emailer {:provider :memory})
                    opts))))

(defn with-providers
  "The whole system, with the real adapters pointed at fake providers on real
  sockets. Slower, and the only way to exercise HTTP, headers and signatures.

  Calls `f` with `{:app :stripe :sendgrid}`."
  ([f] (with-providers {} f))
  ([opts f]
   (recorder/start!)
   (postgres/truncate!)
   (recorder/clear!)
   (let [stripe   (fake-stripe/start!)
         sendgrid (fake-sendgrid/start!)]
     (try
       (f {:stripe   stripe
           :sendgrid sendgrid
           :app      (system/start
                      (assoc (postgres/config)
                             :base-url "http://registry.test"
                             :gateway {:provider :stripe
                                       :base-url (:base-url stripe)
                                       :api-key "sk_test_lab29"}
                             :emailer {:provider :sendgrid
                                       :base-url (:base-url sendgrid)
                                       :api-key "SG.lab29"})
                      opts)})
       (finally
         (fake-stripe/stop! stripe)
         (fake-sendgrid/stop! sendgrid))))))
