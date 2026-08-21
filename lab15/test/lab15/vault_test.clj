(ns lab15.vault-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lab15.vault :as vault]))

(def personal {:name "Aoife Ní Bhriain" :email "aoife@example.ie"})
(def subject "C-123")
(def event-id #uuid "018f7a3e-0000-7000-8000-000000002001")
(def context (vault/personal-context subject event-id))

(deftest a-sealed-value-comes-back-test
  (let [key (vault/generate-key)]
    (is (= personal (vault/unseal key context (vault/seal key context personal))))))

(deftest sealing-twice-gives-different-ciphertext-test
  (testing "a fresh IV each time, so identical values do not look identical"
    (let [key (vault/generate-key)]
      (is (not= (vault/seal key context personal)
                (vault/seal key context personal)))
      (is (= personal
             (vault/unseal key context (vault/seal key context personal)))))))

(deftest the-wrong-key-or-context-does-not-work-test
  (let [key    (vault/generate-key)
        sealed (vault/seal key context personal)]
    (is (thrown? javax.crypto.AEADBadTagException
                 (vault/unseal (vault/generate-key) context sealed)))
    (is (thrown? javax.crypto.AEADBadTagException
                 (vault/unseal key
                               (vault/personal-context "C-999" event-id)
                               sealed)))
    (is (thrown? javax.crypto.AEADBadTagException
                 (vault/unseal key
                               (vault/personal-context subject (random-uuid))
                               sealed)))))

(deftest the-sealed-form-holds-no-plaintext-test
  (let [sealed (vault/seal (vault/generate-key) context personal)
        text   (pr-str sealed)]
    (is (not (str/includes? text "Aoife")))
    (is (not (str/includes? text "example.ie")))))

(deftest destroying-a-key-is-the-erasure-test
  (let [held (vault/hold vault/empty-vault subject (vault/generate-key))]
    (is (some? (vault/key-for held subject)))
    (is (nil? (vault/key-for (vault/destroy held subject) subject)))
    (testing "the pure model does not sanitize retained copies of its before-state"
      (is (some? (vault/key-for held subject))))))

(deftest destroying-is-idempotent-test
  (let [held    (vault/hold vault/empty-vault subject (vault/generate-key))
        once    (vault/destroy held subject)]
    (is (= once (vault/destroy once subject)))))

(deftest destroying-one-subject-leaves-the-others-test
  (let [v (-> vault/empty-vault
              (vault/hold "C-1" (vault/generate-key))
              (vault/hold "C-2" (vault/generate-key)))]
    (is (nil? (vault/key-for (vault/destroy v "C-1") "C-1")))
    (is (some? (vault/key-for (vault/destroy v "C-1") "C-2")))))

(deftest a-subject-key-cannot-be-replaced-or-reused-after-destruction-test
  (let [held      (vault/hold vault/empty-vault subject (vault/generate-key))
        destroyed (vault/destroy held subject)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exists or was destroyed"
                          (vault/hold held subject (vault/generate-key))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exists or was destroyed"
                          (vault/hold destroyed subject (vault/generate-key))))))

(deftest one-key-cannot-be-shared-between-subjects-test
  (let [key  (vault/generate-key)
        held (vault/hold vault/empty-vault "C-1" key)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"another subject"
                          (vault/hold held "C-2" key)))))

(deftest sealed-envelope-versions-and-algorithms-fail-closed-test
  (let [key    (vault/generate-key)
        sealed (vault/seal key context personal)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported sealed-value version"
                          (vault/unseal key context
                                        (assoc sealed :crypto/version 2))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported sealed-value algorithm"
                          (vault/unseal key context
                                        (assoc sealed :algorithm "AES-128-CBC"))))))
