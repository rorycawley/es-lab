(ns lab28.fixture
  (:require [lab28.fake-sendgrid :as fake-sendgrid]
            [lab28.fake-stripe :as fake-stripe]
            [lab28.postgres :as postgres]
            [lab28.recorder :as recorder]
            [lab28.system :as system]))

(defn with-system
  "The whole system, with in-memory providers. Fast, no sockets."
  ([f] (with-system {} f))
  ([opts f]
   (recorder/start!)
   (postgres/truncate!)
   (recorder/clear!)
   (f (system/start (assoc (postgres/config)
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
                             :gateway {:provider :stripe
                                       :base-url (:base-url stripe)
                                       :api-key "sk_test_lab28"}
                             :emailer {:provider :sendgrid
                                       :base-url (:base-url sendgrid)
                                       :api-key "SG.lab28"})
                      opts)})
       (finally
         (fake-stripe/stop! stripe)
         (fake-sendgrid/stop! sendgrid))))))
