(ns registry.uuid-test
  (:require [clojure.test :refer [deftest is testing]]
            [registry.uuid :as uuid]))

(deftest v7-is-valid-uuid
  (testing "returns java.util.UUID" (is (instance? java.util.UUID (uuid/v7))))
  (testing "version bits are 7"     (is (= 7 (.version (uuid/v7)))))
  (testing "variant bits are 2"     (is (= 2 (.variant (uuid/v7)))))
  (testing "timestamp is close to now"
    (let [before (System/currentTimeMillis)
          u      (uuid/v7)
          after  (System/currentTimeMillis)]
      (is (<= before (uuid/timestamp-ms u) after)))))

(deftest v7-is-monotonically-increasing
  (testing "1000 sequential UUIDs are sorted"
    (let [ids (repeatedly 1000 uuid/v7)]
      (is (= ids (sort ids))))))

(deftest v7-str-is-string
  (testing "v7-str returns 36-char string"
    (let [s (uuid/v7-str)]
      (is (string? s))
      (is (= 36 (count s))))))

(deftest no-duplicates
  (testing "10000 UUIDs are unique"
    (let [ids (repeatedly 10000 uuid/v7)]
      (is (= (count ids) (count (distinct ids)))))))
