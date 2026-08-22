(ns cart.config-test
  "Config is read from resources/config.edn by aero.

   Environment overrides are not exercised here: #env reads System/getenv,
   which a JVM cannot mutate in-process. What is testable — and what actually
   breaks — is the shape each profile produces, the validation, and the custom
   reader. The env wiring itself is one tag literal per value in config.edn."
  (:require [aero.core :as aero]
            [cart.app.handle :as handle]
            [cart.config :as config]
            [cart.system :as system]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [matcher-combinators.test :refer [match? thrown-match?]])
  (:import [java.io StringReader]))

(defn- read-inline
  "Reads config from a string, so a tag literal can be tested on its own."
  [s]
  (aero/read-config (StringReader. s)))

;; ---------------------------------------------------------------------------
;; Shape per profile
;; ---------------------------------------------------------------------------

(deftest memory-profile-has-no-database
  (testing "the key is absent, not nil — a nil :db invites destructuring it"
    (let [config (config/read-config :memory)]
      (is (= :memory (:store config)))
      (is (not (contains? config :db))))))

(deftest sqlite-profile-defaults
  (let [config (config/read-config :sqlite)]
    (is (match? {:store :sqlite
                 :http  {:port 8080}
                 :retry {:retries 3 :min-timeout 100 :factor 1.5}
                 :db    {:jdbc-url        "jdbc:sqlite:target/cart-event-store.sqlite3"
                         :pool-size       4
                         :busy-timeout-ms 5000
                         :migrate?        true}}
                config))))

(deftest defaults-are-correctly-typed
  (testing "#long and #double, not strings — cart.system does arithmetic on these"
    (let [{:keys [http retry db]} (config/read-config :sqlite)]
      (is (integer? (:port http)))
      (is (integer? (:retries retry)))
      (is (integer? (:min-timeout retry)))
      (is (double? (:factor retry)))
      (is (integer? (:pool-size db)))
      (is (integer? (:busy-timeout-ms db))))))

(deftest every-store-cart-system-knows-has-a-profile
  (testing "a store with no #profile branch would read as nil and fail later"
    (doseq [store config/stores]
      (is (= store (:store (try (config/read-config store)
                                (catch Exception _ {:store store}))))
          (str "no profile for " store)))))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(deftest postgres-without-a-url-is-rejected-at-startup
  (testing "failing here beats a HikariCP error three components into start-up"
    ;; JDBC_URL is unset in the test JVM, so the postgres profile is incomplete.
    (is (thrown-match? clojure.lang.ExceptionInfo
                       {:store :postgres}
                       (config/read-config :postgres)))))

(deftest unknown-store-names-the-legal-values
  (is (= :postgres (config/parse-store nil)) "defaults to postgres")
  (is (= :postgres (config/parse-store "")) "blank is treated as unset")
  (is (= :sqlite (config/parse-store "sqlite")))
  (is (= :memory (config/parse-store "memory")))
  (is (thrown-match? clojure.lang.ExceptionInfo
                     {:value "mysql" :allowed config/stores}
                     (config/parse-store "mysql"))))

;; ---------------------------------------------------------------------------
;; The #sqlite-path reader
;; ---------------------------------------------------------------------------

(deftest sqlite-path-reader-builds-a-jdbc-url
  (is (= {:url "jdbc:sqlite:/tmp/carts.db"}
         (read-inline "{:url #sqlite-path \"/tmp/carts.db\"}"))))

(deftest sqlite-path-reader-yields-nil-when-unset
  (testing "returning \"jdbc:sqlite:\" would satisfy an enclosing #or and give
            us a URL with no file in it"
    (is (= {:url nil} (read-inline "{:url #sqlite-path nil}")))
    (is (= {:url nil} (read-inline "{:url #sqlite-path \"\"}")))
    (is (= {:url "fallback"}
           (read-inline "{:url #or [#sqlite-path nil \"fallback\"]}")))))

;; ---------------------------------------------------------------------------
;; config.edn must not drift from the in-code fallbacks
;; ---------------------------------------------------------------------------
;;
;; Several namespaces keep their own defaults for callers that build them
;; programmatically — cart.app.handle/default-retry, cart.system's :port, the
;; SQLite adapter's :or values. Those are a legitimate API convenience, not
;; deployment config, so they stay. But they restate numbers that config.edn
;; also states, and nothing stops the two from drifting. These tests are that
;; something.

(deftest retry-defaults-match-cart-app-handle
  (testing "SPEC R4.8's defaults are written in config.edn and in
            cart.app.handle/default-retry; changing one must fail here"
    (is (= handle/default-retry (:retry (config/read-config :memory))))))

(deftest http-port-default-matches-cart-system
  (testing "cart.system falls back to 8080 when :http is omitted"
    (let [port (:port (:http (config/read-config :memory)))
          system-default (-> (system/new-system {:store :memory})
                             :http-server
                             :config
                             (:port 8080))]
      (is (= 8080 port))
      (is (= port system-default)))))

(deftest sqlite-defaults-match-the-adapter
  (testing "the adapter's :or values are what config.edn ships"
    (let [db (:db (config/read-config :sqlite))]
      (is (= {:jdbc-url        "jdbc:sqlite:target/cart-event-store.sqlite3"
              :pool-size       4
              :busy-timeout-ms 5000
              :migrate?        true}
             db)
          "cart.adapter.driven.event-store-sqlite/make-datasource defaults to
           the same values; update both or neither"))))

;; ---------------------------------------------------------------------------
;; The file itself
;; ---------------------------------------------------------------------------

(deftest config-edn-is-the-only-place-the-service-reads-its-environment
  (testing "a stray System/getenv in src is config that escaped aero"
    (let [files (->> (file-seq (io/file "src"))
                     (filter #(.isFile ^java.io.File %))
                     (filter #(str/ends-with? (.getName ^java.io.File %) ".clj")))
          offenders (reduce (fn [offenders f]
                              (let [hits (->> (str/split-lines (slurp f))
                                              (map-indexed vector)
                                              (filter (fn [[_ line]]
                                                        (re-find #"System/getenv|System/getProperty"
                                                                 line)))
                                              vec)]
                                (cond-> offenders
                                  (seq hits)
                                  (assoc (str f)
                                         (mapv (fn [[i line]]
                                                 [(inc i) (str/trim line)])
                                               hits)))))
                            {}
                            files)]
      (is (= {} offenders)
          "every environment read belongs in resources/config.edn"))))

(deftest config-edn-is-on-the-classpath
  (testing "it ships in resources, so an uberjar carries it"
    (is (some? (io/resource "config.edn")))))
