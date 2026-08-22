(ns lab27.demo
  (:gen-class)
  (:require [clojure.string :as str]
            [lab27.catalog.api :as catalog]
            [lab27.corpus :as corpus]
            [lab27.naive-search :as naive]
            [lab27.ordering.api :as ordering]
            [lab27.postgres :as postgres]
            [lab27.recorder :as recorder]
            [lab27.system :as system]
            [next.jdbc :as jdbc]))

(def vanilla   #uuid "0f1c2b3a-0000-4000-8000-000000000026")
(def pistachio #uuid "0f1c2b3a-0000-4000-8000-000000000027")
(def chocolate #uuid "0f1c2b3a-0000-4000-8000-000000000028")
(def rule "  ──────────────────────────────────────────────────────────────")

(defn- stock! [catalog product-id product-name price-cents description]
  (catalog/change-price! catalog {:command-id (random-uuid)
                                  :correlation-id (random-uuid)
                                  :product-id product-id
                                  :product-name product-name
                                  :price-cents price-cents})
  (catalog/describe-product! catalog {:command-id (random-uuid)
                                      :correlation-id (random-uuid)
                                      :product-id product-id
                                      :description description}))

(defn- show!
  "One query, and what came back."
  [catalog query]
  (let [{:keys [found did-you-mean]} (catalog/search catalog {:query query})]
    (println (format "    %-20s  %s" query
                     (cond
                       found        (str/join ", " (map :product-name found))
                       did-you-mean (str "nothing — did you mean "
                                         (str/join " or " (map :product-name did-you-mean)) "?")
                       :else        "nothing")))))

(defn- scan-of
  "The innermost access method in a plan — the line that says how rows were
  actually reached, rather than what was done with them afterwards."
  [plan]
  (let [lines (map str/trim (str/split-lines plan))
        line  (or (first (filter #(str/includes? % "Index Scan") lines))
                  (first (filter #(str/includes? % "Seq Scan") lines))
                  "?")]
    (-> line
        (str/replace #"^->\s+" "")
        (str/replace #" on product$" "")
        (str/replace #"^Bitmap Index Scan on " "GIN · "))))

(defn -main [& _]
  (recorder/start!)
  (postgres/truncate!)
  (let [{:keys [catalog ordering] :as app} (system/start (postgres/config))]
    (stock! catalog vanilla "vanilla" 300 "a creamy vanilla flavour with real pods")
    (stock! catalog pistachio "pistachio" 450 "sea salt and roasted nuts")
    (stock! catalog chocolate "chocolate" 350 "dark and bitter, no pistachio at all")

    (println)
    (println "  Search, in the database you already have.")
    (println rule)
    (println)
    (println "  Words, not substrings.")
    (show! catalog "flavours")
    (show! catalog "creamy")
    (show! catalog "anill")
    (println "    LIKE '%anill%' would have matched vanilla. This does not,")
    (println "    because \"anill\" is not a word anything here contains.")
    (println)

    (println rule)
    (println)
    (println "  A name beats a mention.")
    (show! catalog "pistachio")
    (println "    pistachio is called that. chocolate merely says it.")
    (println "    setweight put the name in band A and the description in B.")
    (println)

    (println rule)
    (println)
    (println "  Whatever the user types.")
    (show! catalog "\"sea salt\"")
    (show! catalog "\"salt sea\"")
    (show! catalog "bitter -vanilla")
    (show! catalog "((")
    (println "    The last one is not an error. websearch_to_tsquery never")
    (println "    raises, which is why it is the one you hand a person.")
    (println)

    (println rule)
    (println)
    (println "  A misspelling is a suggestion, not a result.")
    (show! catalog "pistacio")
    (show! catalog "xylophone")
    (println "    Trigrams compare letters. They have no idea what you meant,")
    (println "    so what they return is a question, not an answer.")
    (println)

    (println rule)
    (println)
    (println "  Two owners. Two lists. There is no third.")
    (system/relay-catalog! app)
    (ordering/place-order! ordering {:order-id (random-uuid)
                                     :correlation-id (random-uuid)
                                     :product-id pistachio
                                     :quantity 2
                                     :customer-email "ada@example.com"})
    (let [products (:found (catalog/search catalog {:query "pistachio"}))
          orders   (:found (ordering/search ordering {:query "pistachio"}))]
      (println (format "    catalog   %d product(s): %s"
                       (count products) (str/join ", " (map :product-name products))))
      (println (format "    ordering  %d order(s)"  (count orders))))
    (println "    Each module indexes what it owns, so nothing is kept in sync.")
    (println "    The price of that is above: these cannot be one ranked list,")
    (println "    because no single index contains both.")
    (println)
    (println (format "    searching for a customer: %s"
                     (:no-matches (ordering/search ordering {:query "ada@example.com"}))))
    (println "    Orders are searchable by what was ordered, never by who.")
    (println)

    (println rule)
    (println)
    (printf "  What the planner does with %,d products.%n" corpus/rows)
    (println)
    (let [db  (jdbc/get-datasource (:catalog (postgres/config)))
          _   (corpus/seed! db)
          fts "SELECT product_id FROM catalog.product, websearch_to_tsquery('english', ?) q
                WHERE search_document @@ q
                ORDER BY ts_rank_cd(search_document, q, 32) DESC LIMIT 10"
          like-params [(str "%" corpus/rare-term "%") 10]]
      (println (format "    %-22s %-34s %s" "" "chosen" "forbidden to scan"))
      (doseq [[label sql params]
              [["search_document @@ q" fts [corpus/rare-term]]
               ["LIKE '%szechuan%'"    naive/like-sql like-params]]]
        (println (format "    %-22s %-34s %s"
                         label
                         (scan-of (naive/explain db sql params))
                         (scan-of (naive/explain-without-seqscan db sql params)))))
      (println)
      (println "    The right-hand column is the one that matters. Forbid the")
      (println "    sequential scan and LIKE does it anyway, because there is")
      (println "    no index in this database it could use. The left-hand")
      (println "    column depends on table statistics. That one does not."))
    (println)
    (println rule)
    (println))

  (system/stop-telemetry!)
  (shutdown-agents))
