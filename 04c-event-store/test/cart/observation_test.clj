(ns cart.observation-test
  (:require [cart.observation :as observation]
            [clojure.test :refer [deftest is testing]])
  (:import [java.util UUID]))

(def cart-id (UUID/fromString "10000000-0000-0000-0000-000000000001"))
(def old-ring {:active-key-id "old"
               :keys {"old" "old-test-signing-key-with-enough-entropy"}})
(def rotated-ring {:active-key-id "new"
                   :keys {"old" "old-test-signing-key-with-enough-entropy"
                          "new" "new-test-signing-key-with-enough-entropy"}})

(deftest observations-round-trip-and-are-cart-bound-data
  (let [marker (observation/issue old-ring cart-id 7)]
    (is (= {:ok {:cart-id cart-id :revision 7}}
           (observation/verify old-ring marker)))
    (is (= {:error :invalid-cart-observation}
           (observation/verify old-ring (str marker "x"))))))

(deftest retained-keys-preserve-observations-without-byte-equality
  (let [old-marker (observation/issue old-ring cart-id 7)
        new-marker (observation/issue rotated-ring cart-id 7)
        old-value  (:ok (observation/verify rotated-ring old-marker))
        new-value  (:ok (observation/verify rotated-ring new-marker))]
    (is (not= old-marker new-marker))
    (is (observation/same-observation? old-value new-value))))

(deftest invalid-versions-keys-and-payloads-are-rejected
  (doseq [marker [nil "" "v2.old.payload.signature"
                  "v1.missing.payload.signature" "v1.old.bad.bad"]]
    (testing (pr-str marker)
      (is (= {:error :invalid-cart-observation}
             (observation/verify old-ring marker))))))
