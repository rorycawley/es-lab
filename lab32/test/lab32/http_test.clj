(ns lab32.http-test
  "The driving adapter, called as a function.

  Lab 23's arrangement: the handler is a function from a map to a map, so
  nearly all of this needs no socket. The one test that does prove there is a
  server lives in `server_test.clj`."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lab32.fixture :as fixture]
            [lab32.system :as system])
  (:import (java.io ByteArrayInputStream)))

(defn- request
  "A ring request map.

  The query string is split out of `uri` rather than left on it, because that
  is what a real server hands the handler: reitit matches on the path alone,
  and `wrap-params` reads `:query-string`. Leaving `?account=...` on `:uri`
  produces a 404 that looks like an empty result."
  ([method uri] (request method uri nil))
  ([method uri body]
   (let [[path query] (str/split uri #"\?" 2)]
     (cond-> {:request-method method :uri path}
       query (assoc :query-string query)
       body  (assoc :body (ByteArrayInputStream.
                           (.getBytes (json/write-str body) "UTF-8")))))))

(defn- call
  [sys req]
  (let [response ((system/handler sys) req)]
    (cond-> response
      ;; Not every response has a JSON body. Reitit answers a known path with
      ;; an unsupported method with a 405 and an empty one, and parsing that
      ;; throws an EOF from inside the reader rather than failing the
      ;; assertion the test was actually making.
      (seq (:body response)) (assoc :parsed (json/read-str (:body response)
                                                           :key-fn keyword
                                                           :bigdec true)))))

(defn- open! [sys account]
  (call sys (request :post "/accounts" {:account-id (str account) :holder "Ada"})))

;; ---------------------------------------------------------------------------
;; Commands
;; ---------------------------------------------------------------------------

(deftest an-account-is-opened-and-then-readable-test
  (fixture/with-system
    (fn [sys]
      (let [account (random-uuid)]
        (is (= 201 (:status (open! sys account))))
        (let [{:keys [status parsed]} (call sys (request :get (str "/accounts/" account)))]
          (is (= 200 status))
          (is (= "Ada" (:holder parsed)))
          (is (= "open" (:status parsed)))
          (is (zero? (compare 0M (:balance parsed)))))))))

(deftest an-act-is-a-resource-that-gets-created-test
  ;; Lab 23's rule. A deposit is a thing that happened, so it is POSTed to a
  ;; collection of deposits -- not PATCHed onto the account with a delta the
  ;; server has to interpret.
  (fixture/with-system
    (fn [sys]
      (let [account (random-uuid)]
        (open! sys account)
        (is (= 201 (:status (call sys (request :post (str "/accounts/" account "/deposits")
                                               {:amount 12000})))))
        (is (= 201 (:status (call sys (request :post (str "/accounts/" account "/withdrawals")
                                               {:amount 2000})))))
        (let [{:keys [parsed]} (call sys (request :get (str "/accounts/" account)))]
          (is (zero? (compare 10000M (:balance parsed)))))))))

(deftest a-refusal-is-an-answer-and-not-an-outage-test
  (fixture/with-system
    (fn [sys]
      (let [account (random-uuid)]
        (open! sys account)
        (call sys (request :post (str "/accounts/" account "/deposits") {:amount 100}))

        (testing "an overdraft is a conflict, not a 500"
          (let [{:keys [status parsed]}
                (call sys (request :post (str "/accounts/" account "/withdrawals")
                                   {:amount 500}))]
            (is (= 409 status))
            (is (= "insufficient-funds" (:error parsed)))))

        (testing "opening the same account twice is a conflict too"
          (is (= 409 (:status (open! sys account)))))

        (testing "an unknown account is a 404"
          (is (= 404 (:status (call sys (request :get (str "/accounts/" (random-uuid))))))))

        (testing "a malformed identifier is a 400"
          (is (= 400 (:status (call sys (request :get "/accounts/not-a-uuid"))))))))))

(deftest a-floating-point-amount-is-refused-at-the-edge-test
  ;; Gotcha #10 where it actually matters: the boundary where somebody else's
  ;; JSON arrives. `:bigdec true` on the reader means `10000.50` is a
  ;; BigDecimal by the time `money/of` sees it, so a legitimate decimal is
  ;; accepted -- and a value that genuinely cannot be money is not.
  (fixture/with-system
    (fn [sys]
      (let [account (random-uuid)]
        (open! sys account)
        (testing "a decimal in the request body is fine"
          (is (= 201 (:status (call sys (request :post (str "/accounts/" account "/deposits")
                                                 {:amount 10000.50M}))))))
        (testing "a missing amount is a 400"
          (is (= 400 (:status (call sys (request :post (str "/accounts/" account "/deposits")
                                                 {}))))))))))

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(deftest the-read-model-is-queryable-once-the-message-has-moved-test
  (fixture/with-system
    (fn [sys]
      (let [account (random-uuid)]
        (open! sys account)
        (call sys (request :post (str "/accounts/" account "/deposits") {:amount 45000}))

        (is (empty? (:flagged (:parsed (call sys (request :get "/compliance/flagged")))))
            "nothing has drained it yet")

        (system/settle! sys)

        (let [flagged (:flagged (:parsed (call sys (request :get "/compliance/flagged"))))]
          (is (= 1 (count flagged)))
          (is (= (str account) (:account-id (first flagged))))
          (is (= "credit" (:direction (first flagged)))))

        (testing "and filterable by account"
          (is (= 1 (count (:flagged (:parsed (call sys (request :get (str "/compliance/flagged?account=" account))))))))
          (is (zero? (count (:flagged (:parsed (call sys (request :get (str "/compliance/flagged?account=" (random-uuid))))))))))))))

;; ---------------------------------------------------------------------------
;; Phase 4 — the audit endpoints
;; ---------------------------------------------------------------------------

(deftest the-full-history-of-an-account-is-served-test
  (fixture/with-system
    (fn [sys]
      (let [account (random-uuid)]
        (open! sys account)
        (call sys (request :post (str "/accounts/" account "/deposits") {:amount 5000}))
        (call sys (request :post (str "/accounts/" account "/withdrawals") {:amount 1000}))

        (let [{:keys [status parsed]}
              (call sys (request :get (str "/audit/account/" account)))]
          (is (= 200 status))
          (is (= ["accounts/account-opened"
                  "accounts/money-deposited"
                  "accounts/money-withdrawn"]
                 (mapv :event/type (:events parsed)))))))))

(deftest the-ad-hoc-query-is-served-test
  (fixture/with-system
    (fn [sys]
      (let [account (random-uuid)]
        (open! sys account)
        (doseq [amount [500 25000 900]]
          (call sys (request :post (str "/accounts/" account "/deposits") {:amount amount})))

        (testing "by type"
          (is (= 3 (count (:events (:parsed (call sys (request
                                                       :get "/audit/query?type=accounts/money-deposited"))))))))

        (testing "by a JSONB predicate over the whole history"
          (is (= 1 (count (:events (:parsed (call sys (request
                                                       :get "/audit/query?type=accounts/money-deposited&min=10000"))))))))

        (testing "by time"
          (let [future (str (.plusSeconds (java.time.Instant/now) 3600))]
            (is (zero? (count (:events (:parsed (call sys (request
                                                           :get (str "/audit/query?from=" future))))))))))))))

(deftest replay-is-a-post-and-rebuilds-the-read-model-test
  ;; A GET that truncates a table is a GET a link checker can fire. The build
  ;; spec lists this endpoint as a GET; see `routes.clj` for why it is not.
  (fixture/with-system
    (fn [sys]
      (let [account (random-uuid)]
        (open! sys account)
        (call sys (request :post (str "/accounts/" account "/deposits") {:amount 45000}))
        (system/settle! sys)
        (is (= 1 (count (:flagged (:parsed (call sys (request :get "/compliance/flagged")))))))

        (testing "a GET is not allowed on it"
          ;; 405, not 404: the path exists and the method does not, which is
          ;; the distinction that matters here. The build spec lists this as a
          ;; GET; `routes.clj` explains why a GET that truncates a table is a
          ;; GET a link checker can fire.
          (is (= 405 (:status (call sys (request :get "/audit/replay/compliance"))))))

        (testing "the POST clears and rebuilds"
          (let [{:keys [status parsed]}
                (call sys (request :post "/audit/replay/compliance"))]
            (is (= 202 status))
            (is (= 1 (:republished parsed))))
          (is (zero? (count (:flagged (:parsed (call sys (request :get "/compliance/flagged")))))))
          (system/settle! sys)
          (is (= 1 (count (:flagged (:parsed (call sys (request :get "/compliance/flagged")))))))))

      (testing "an unknown module is refused"
        (is (= 404 (:status (call sys (request :post "/audit/replay/nonsense")))))))))

(deftest an-unknown-route-is-a-json-404-test
  (fixture/with-system
    (fn [sys]
      (let [{:keys [status parsed]} (call sys (request :get "/nope"))]
        (is (= 404 status))
        (is (= "not-found" (:error parsed)))))))
