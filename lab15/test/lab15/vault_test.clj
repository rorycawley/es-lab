(ns lab15.vault-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lab15.vault :as vault]))

(def personal {:name "Aoife Ní Bhriain" :email "aoife@example.ie"})

(deftest a-sealed-value-comes-back-test
  (let [key (vault/generate-key)]
    (is (= personal (vault/unseal key (vault/seal key personal))))))

(deftest sealing-twice-gives-different-ciphertext-test
  (testing "a fresh IV each time, so identical values do not look identical"
    (let [key (vault/generate-key)]
      (is (not= (vault/seal key personal) (vault/seal key personal)))
      (is (= personal (vault/unseal key (vault/seal key personal)))))))

(deftest the-wrong-key-does-not-work-test
  (let [sealed (vault/seal (vault/generate-key) personal)]
    (is (thrown? javax.crypto.AEADBadTagException
                 (vault/unseal (vault/generate-key) sealed)))))

(deftest the-sealed-form-holds-no-plaintext-test
  (let [sealed (vault/seal (vault/generate-key) personal)
        text   (pr-str sealed)]
    (is (not (str/includes? text "Aoife")))
    (is (not (str/includes? text "example.ie")))))

(deftest destroying-a-key-is-the-erasure-test
  (let [subject "C-123"
        vault   (vault/hold vault/empty-vault subject (vault/generate-key))]
    (is (some? (vault/key-for vault subject)))
    (is (nil? (vault/key-for (vault/destroy vault subject) subject)))))

(deftest destroying-is-idempotent-test
  (let [subject "C-123"
        held    (vault/hold vault/empty-vault subject (vault/generate-key))
        once    (vault/destroy held subject)]
    (is (= once (vault/destroy once subject)))))

(deftest destroying-one-subject-leaves-the-others-test
  (let [v (-> vault/empty-vault
              (vault/hold "C-1" (vault/generate-key))
              (vault/hold "C-2" (vault/generate-key)))]
    (is (nil? (vault/key-for (vault/destroy v "C-1") "C-1")))
    (is (some? (vault/key-for (vault/destroy v "C-1") "C-2")))))
