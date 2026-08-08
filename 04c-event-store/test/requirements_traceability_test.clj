(ns requirements-traceability-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def test-id-pattern #"(?m)^\| (UC-[0-9]{2}/S[0-9]{2}/TC[0-9]{2}) \|")

(defn- ids-in [path]
  (map second (re-seq test-id-pattern (slurp path))))

(defn- traceability-rows []
  (for [line (str/split-lines (slurp "docs/requirements-traceability.md"))
        :let [[_ id iteration outcome verification]
              (re-matches
               #"\| (UC-[0-9]{2}/S[0-9]{2}/TC[0-9]{2}) \| ([1-6]) \| ([^|]+) \| ([^|]+) \|"
               line)]
        :when id]
    {:id id
     :iteration iteration
     :outcome (str/trim outcome)
     :verification (str/trim verification)}))

(defn- spec-rows []
  (for [line (str/split-lines (slurp "SPEC2.md"))
        :let [fields (mapv str/trim (str/split line #"\|" -1))
              id     (get fields 1)]
        :when (and id (re-matches #"UC-[0-9]{2}/S[0-9]{2}/TC[0-9]{2}" id))]
    {:id id :then (get fields 5)}))

(defn- expected-outcome [then]
  (cond
    (str/includes? then "other receives invalid input") "success + invalid"
    (and (str/includes? then "accepted")
         (or (str/includes? then "reported as conflicts")
             (str/includes? then "reported as a conflict"))) "success + conflict"
    (str/includes? then "invalid input rather than") "invalid"
    (str/includes? then "concurrent-change conflict rather than") "conflict"
    (str/includes? then "rejected as invalid input") "invalid"
    (str/includes? then "receives invalid input") "invalid"
    (str/includes? then "business rejection") "rejected"
    (str/includes? then "rejected as a conflict") "conflict"
    (str/includes? then "reported as a conflict") "conflict"
    :else "success"))

(deftest all-spec-cases-have-one-traceability-row
  (let [spec-ids  (ids-in "SPEC2.md")
        trace-ids (map :id (traceability-rows))]
    (is (= 91 (count spec-ids)))
    (is (= 91 (count trace-ids)))
    (is (= (set spec-ids) (set trace-ids)))
    (is (= (count trace-ids) (count (set trace-ids))))))

(deftest every-row-declares-an-swr-008-outcome
  (let [allowed #{"success"
                  "invalid"
                  "rejected"
                  "conflict"
                  "success + invalid"
                  "success + conflict"}]
    (is (every? #(contains? allowed (:outcome %))
                (traceability-rows)))))

(deftest traceability-outcomes-agree-with-the-spec-expectations
  (let [traced (into {} (map (juxt :id :outcome) (traceability-rows)))]
    (doseq [{:keys [id then]} (spec-rows)]
      (is (= (expected-outcome then) (get traced id))
          (str id " expected from SPEC2 Then: " then)))))

(deftest iteration-allocation-is-complete-and-stable
  (is (= {"1" 14
          "2" 23
          "3" 7
          "4" 19
          "5" 18
          "6" 10}
         (frequencies (map :iteration (traceability-rows)))))
  (let [verified-ids
        (set (concat
              (for [case (range 1 9)]
                (format "UC-01/S01/TC%02d" case))
              (for [case (range 1 6)]
                (format "UC-01/S02/TC%02d" case))
              ["UC-02/S01/TC01"]))
        rows (traceability-rows)]
    (is (= verified-ids
           (set (map :id
                     (filter #(str/starts-with? (:verification %)
                                                "Verified:")
                             rows)))))
    (is (every? #(= "Planned" (:verification %))
                (remove (comp verified-ids :id) rows)))))

(deftest every-traced-case-belongs-to-a-prepared-slice
  (let [prepared-slices
        (set (map second
                  (re-seq
                   #"(?m)^\| (UC-[0-9]{2}/S[0-9]{2}) \| [^|]+ \| Must \| Prepared \|$"
                   (slurp "SPEC2.md"))))]
    (is (= 24 (count prepared-slices)))
    (doseq [{:keys [id]} (traceability-rows)]
      (is (contains? prepared-slices
                     (first (str/split id #"/TC")))
          id))))
