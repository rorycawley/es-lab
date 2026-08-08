(ns cart.acceptance.iteration-one
  "The 14 Iteration 1 SPEC2 acceptance scenarios, parameterized by adapter."
  (:require [cart.port.out.projection-store :as projection-store]
            [cheshire.core :as json]
            [clojure.test :refer [is testing]])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.time Duration]))

(def product-a "20000000-0000-0000-0000-000000000001")
(def product-b "20000000-0000-0000-0000-000000000002")
(def request-a "30000000-0000-0000-0000-000000000001")
(def request-b "30000000-0000-0000-0000-000000000002")
(def request-c "30000000-0000-0000-0000-000000000003")

(defn- post! [handler path body]
  (let [bytes (.getBytes (json/generate-string body) StandardCharsets/UTF_8)
        response (handler {:request-method :post
                           :uri path
                           :headers {"content-type" "application/json"}
                           :body (ByteArrayInputStream. bytes)})]
    {:status (:status response)
     :body (json/parse-string (:body response) true)}))

(defn- add! [context body]
  (post! (:handler context) "/commands/add-product-item" body))

(defn- view! [context cart-id]
  (post! (:handler context) "/queries/view-cart" {:cart-id cart-id}))

(defn- first-add [request-id product-id quantity]
  {:request-id request-id
   :product-item {:product-id product-id :quantity quantity}})

(defn- existing-add [request-id cart-result product-id quantity]
  {:request-id request-id
   :cart-id (:cart-id cart-result)
   :cart-observation (:cart-observation cart-result)
   :product-item {:product-id product-id :quantity quantity}})

(defn- success-result [response]
  (is (= 200 (:status response)) (pr-str response))
  (is (= "success" (get-in response [:body :outcome])) (pr-str response))
  (get-in response [:body :result]))

(defn- completed [future]
  (let [result (deref future 10000 ::timeout)]
    (is (not= ::timeout result) "Concurrent request did not complete")
    result))

(defn- concurrent [left-fn right-fn]
  (let [start (promise)
        left  (future @start (left-fn))
        right (future @start (right-fn))]
    (deliver start true)
    [(completed left) (completed right)]))

(defn- cart-history-count [context cart-id]
  (count (projection-store/read-cart-history
          (:store context)
          (java.util.UUID/fromString cart-id))))

(defn- case-context [new-context]
  (let [context (new-context)]
    (is (= {:streams 0 :events 0 :commands 0}
           ((:counts context))))
    context))

(defn assert-iteration-one! [new-context]
  (testing "UC-01/S01/TC01 first addition establishes and returns a cart"
    (let [context (case-context new-context)
          result  (success-result
                   (add! context (first-add request-a product-a 2)))]
      (is (uuid? (parse-uuid (:cart-id result))))
      (is (= "open" (:status result)))
      (is (= [{:product-id product-a :quantity 2}] (:items result)))
      (is (string? (:cart-observation result)))
      (is (= {:streams 1 :events 1 :commands 1} ((:counts context))))))

  (testing "UC-01/S01/TC02 independent requests establish independent carts"
    (let [context (case-context new-context)
          left    (success-result (add! context (first-add request-a product-a 1)))
          right   (success-result (add! context (first-add request-b product-a 1)))
          changed-left
          (success-result
           (add! context (existing-add request-c left product-a 1)))
          current-right (success-result (view! context (:cart-id right)))]
      (is (not= (:cart-id left) (:cart-id right)))
      (is (= [{:product-id product-a :quantity 2}] (:items changed-left)))
      (is (= (select-keys right [:cart-id :status :items])
             (select-keys current-right [:cart-id :status :items])))
      (is (= {:streams 2 :events 3 :commands 3} ((:counts context))))))

  (testing "UC-01/S01/TC03 first-add replay survives later cart changes"
    (let [context  (case-context new-context)
          command  (first-add request-a product-a 2)
          original (add! context command)
          first    (success-result original)
          changed  (success-result
                    (add! context (existing-add request-b first product-b 1)))
          replayed (add! context command)
          current  (success-result (view! context (:cart-id first)))]
      (is (= original replayed))
      (is (= (select-keys changed [:cart-id :status :items])
             (select-keys current [:cart-id :status :items])))
      (is (= 2 (cart-history-count context (:cart-id first))))
      (is (= {:streams 1 :events 2 :commands 2} ((:counts context))))))

  (testing "UC-01/S01/TC04 first-add request ID cannot identify other input"
    (let [context  (case-context new-context)
          original (success-result
                    (add! context (first-add request-a product-a 2)))
          misuse   (add! context (first-add request-a product-b 2))
          current  (success-result (view! context (:cart-id original)))]
      (is (= 400 (:status misuse)))
      (is (= "invalid" (get-in misuse [:body :outcome])))
      (is (= original current))
      (is (= {:streams 1 :events 1 :commands 1} ((:counts context))))))

  (testing "UC-01/S01/TC05 missing or malformed request ID creates nothing"
    (let [context (case-context new-context)]
      (doseq [command [(dissoc (first-add request-a product-a 2) :request-id)
                       (first-add "not-a-uuid" product-a 2)]]
        (let [response (add! context command)]
          (is (= 400 (:status response)))
          (is (= "invalid" (get-in response [:body :outcome])))))
      (is (= {:streams 0 :events 0 :commands 0} ((:counts context))))))

  (testing "UC-01/S01/TC06 equal concurrent first-add deliveries commit once"
    (let [context (case-context new-context)
          command (first-add request-a product-a 2)
          [left right] (concurrent #(add! context command)
                                   #(add! context command))]
      (is (= 200 (:status left) (:status right)))
      (is (= (:body left) (:body right)))
      (is (= {:streams 1 :events 1 :commands 1} ((:counts context))))))

  (testing "UC-01/S01/TC07 invalid attempt does not consume request ID"
    (let [context (case-context new-context)
          invalid (add! context (first-add request-a product-a 0))
          valid   (add! context (first-add request-a product-a 2))]
      (is (= 400 (:status invalid)))
      (success-result valid)
      (is (= {:streams 1 :events 1 :commands 1} ((:counts context))))))

  (testing "UC-01/S01/TC08 non-equal global request race has one winner"
    (let [context (case-context new-context)
          [left right]
          (concurrent #(add! context (first-add request-a product-a 2))
                      #(add! context (first-add request-a product-b 2)))]
      (is (= #{200 400} #{(:status left) (:status right)}))
      (is (= #{"success" "invalid"}
             #{(get-in left [:body :outcome])
               (get-in right [:body :outcome])}))
      (is (= {:streams 1 :events 1 :commands 1} ((:counts context))))))

  (testing "UC-01/S02/TC01 existing product quantity is aggregated"
    (let [context (case-context new-context)
          first   (success-result (add! context (first-add request-a product-a 2)))
          result  (success-result
                   (add! context (existing-add request-b first product-a 3)))]
      (is (= "open" (:status result)))
      (is (= [{:product-id product-a :quantity 5}] (:items result)))
      (is (= {:streams 1 :events 2 :commands 2} ((:counts context))))))

  (testing "UC-01/S02/TC02 items are sorted by product UUID"
    (let [context (case-context new-context)
          first   (success-result (add! context (first-add request-a product-b 1)))
          result  (success-result
                   (add! context (existing-add request-b first product-a 1)))]
      (is (= [product-a product-b] (mapv :product-id (:items result))))))

  (testing "UC-01/S02/TC03 existing-add replay preserves original result"
    (let [context (case-context new-context)
          first   (success-result (add! context (first-add request-a product-a 1)))
          command (existing-add request-b first product-a 1)
          original (add! context command)
          second   (success-result original)
          changed  (success-result
                    (add! context (existing-add request-c second product-b 1)))
          replayed (add! context command)
          current  (success-result (view! context (:cart-id first)))]
      (is (= original replayed))
      (is (= (select-keys changed [:cart-id :status :items])
             (select-keys current [:cart-id :status :items])))
      (is (= 3 (cart-history-count context (:cart-id first))))
      (is (= {:streams 1 :events 3 :commands 3} ((:counts context))))))

  (testing "UC-01/S02/TC04 equal concurrent existing additions commit once"
    (let [context (case-context new-context)
          first   (success-result (add! context (first-add request-a product-a 1)))
          command (existing-add request-b first product-a 2)
          [left right] (concurrent #(add! context command) #(add! context command))]
      (is (= 200 (:status left) (:status right)))
      (is (= (:body left) (:body right)))
      (is (= [{:product-id product-a :quantity 3}]
             (get-in left [:body :result :items])))
      (is (= {:streams 1 :events 2 :commands 2} ((:counts context))))))

  (testing "UC-01/S02/TC05 elapsed time does not expire observation"
    (let [context (case-context new-context)
          first   (success-result (add! context (first-add request-a product-a 1)))]
      (swap! (:clock context) #(.plus ^java.time.Instant % (Duration/ofDays 365)))
      (let [result (success-result
                    (add! context (existing-add request-b first product-b 1)))]
        (is (= [{:product-id product-a :quantity 1}
                {:product-id product-b :quantity 1}]
               (:items result)))
        (is (= {:streams 1 :events 2 :commands 2} ((:counts context)))))))

  (testing "UC-02/S01/TC01 returned cart identifier can be viewed"
    (let [context (case-context new-context)
          added   (success-result (add! context (first-add request-a product-a 1)))
          viewed  (success-result (view! context (:cart-id added)))]
      (is (= (select-keys added [:cart-id :status :items])
             (select-keys viewed [:cart-id :status :items])))
      (is (string? (:cart-observation viewed)))
      (is (= 1 (cart-history-count context (:cart-id added))))
      (is (= {:streams 1 :events 1 :commands 1} ((:counts context)))))))
