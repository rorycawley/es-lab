(ns lab34.registry-test
  "The check a configuration file cannot perform.

  Everything in `definition_test` is answerable about a definition alone. This
  namespace is about the questions that need to know what is *running*, which
  is why they live in the registry and why no environment variable, feature
  flag or YAML file can ever ask them."
  (:require [clojure.test :refer [deftest is testing]]
            [lab34.fixture :as fixture]
            [lab34.instance :as instance]
            [lab34.migration :as migration]
            [lab34.onboarding :as onboarding]
            [lab34.registry :as registry]))

(def v1-and-v2 (fixture/registry-with onboarding/v1 onboarding/v2))

(defn- sitting-in-sanctions [id]
  (-> (instance/start onboarding/v1 id)
      (instance/observe-all (registry/resolver v1-and-v2)
                            [(fixture/event :identity-verified fixture/day-1)])))

(defn- on-v2-in-sanctions [id]
  (-> (instance/start onboarding/v2 id)
      (instance/observe-all (registry/resolver v1-and-v2)
                            [(fixture/event :identity-verified fixture/day-1)])))

(defn- finished [id]
  (-> (instance/start onboarding/v1 id)
      (instance/observe-all (registry/resolver v1-and-v2)
                            [(fixture/event :identity-rejected fixture/day-1)])))

;; ---------------------------------------------------------------------------
;; Additive change: nobody is in the new state, so nobody is stranded
;; ---------------------------------------------------------------------------

(deftest adding-a-step-is-always-safe-test
  ;; v2 adds `:awaiting-manual`. No v1 instance can be in a state v1 never
  ;; declared, so the check is vacuous — which is the correct answer rather
  ;; than a special case.
  (let [live [(sitting-in-sanctions fixture/ada)]]
    (is (= [1 2] (registry/versions
                  (registry/publish (fixture/registry-with onboarding/v1)
                                    onboarding/v2 live onboarding/handled-commands))))))

;; ---------------------------------------------------------------------------
;; Subtractive change: the interesting one
;; ---------------------------------------------------------------------------

(deftest removing-a-step-somebody-is-in-is-refused-test
  ;; v3 merges identity and sanctions into one screening step, so
  ;; `:awaiting-sanctions` is gone. Ada is standing in it.
  (let [live    [(sitting-in-sanctions fixture/ada)]
        publish #(registry/publish v1-and-v2 onboarding/v3 live onboarding/handled-commands)]
    (is (= :cannot-publish (fixture/reason publish)))
    (is (= [(str "instance " fixture/ada " is in :awaiting-sanctions under v1,"
                 " which v3 does not declare")]
           (fixture/problems-of publish)))))

(deftest the-same-change-is-allowed-once-they-have-drained-test
  ;; Nothing about v3 is wrong. It is wrong *now*, and that is a distinction a
  ;; config file cannot make.
  (let [done [(finished fixture/ada)]]
    (is (= [1 2 3] (registry/versions
                    (registry/publish v1-and-v2 onboarding/v3 done
                                      onboarding/handled-commands))))))

(deftest a-completed-instance-strands-nobody-test
  ;; The three populations, and only one of them matters. `:rejected` is
  ;; terminal, so Ada is not going anywhere and v3 need not describe her.
  (let [done (finished fixture/ada)]
    (is (not (instance/in-flight? done (registry/resolver v1-and-v2))))
    (is (= {} (registry/in-flight v1-and-v2 [done])))))

(deftest in-flight-groups-by-the-version-each-is-pinned-to-test
  ;; The operational question this whole design exists to answer.
  (let [old   (sitting-in-sanctions fixture/ada)
        newer (-> (instance/start onboarding/v2 fixture/grace)
                  (instance/observe-all (registry/resolver v1-and-v2)
                                        [(fixture/event :identity-verified fixture/day-1)]))
        done  (finished fixture/alan)]
    (is (= {1 [fixture/ada] 2 [fixture/grace]}
           (update-vals (registry/in-flight v1-and-v2 [old newer done])
                        #(mapv :process/id %))))))

;; ---------------------------------------------------------------------------
;; Publication is immutable
;; ---------------------------------------------------------------------------

(deftest a-published-version-cannot-be-edited-test
  ;; The property pinning depends on. If v1 could be changed after the fact,
  ;; pinning to v1 would guarantee nothing at all — an instance would still
  ;; fold under whatever v1 had become.
  (let [amended (assoc-in onboarding/v1 [:states :awaiting-sanctions :on :sanctions-hit]
                          :awaiting-sanctions)]
    (is (= :cannot-publish
           (fixture/reason #(registry/publish v1-and-v2 amended [] onboarding/handled-commands))))
    (is (some #(re-find #"version 1 is already published" %)
              (fixture/problems-of
               #(registry/publish v1-and-v2 amended [] onboarding/handled-commands))))))

(deftest an-incomplete-definition-cannot-be-published-test
  ;; `definition/problems` is folded into the publication check, so a broken
  ;; process is impossible to install rather than something an instance
  ;; discovers by getting stuck in it.
  (let [broken (assoc onboarding/v3 :initial :nowhere)]
    (is (= :cannot-publish
           (fixture/reason #(registry/publish v1-and-v2 broken [] onboarding/handled-commands))))))

(deftest a-definition-for-another-process-is-refused-test
  (let [payments (assoc onboarding/v3 :process/name :payments)]
    (is (some #(re-find #"this registry is for :onboarding" %)
              (fixture/problems-of
               #(registry/publish v1-and-v2 payments [] onboarding/handled-commands))))))

;; ---------------------------------------------------------------------------
;; Release: the change and its migration, together
;; ---------------------------------------------------------------------------

(deftest publish-and-migrate-cannot-be-sequenced-test
  ;; The deadlock that makes `release` necessary, demonstrated in both
  ;; directions. It is not an accident of this implementation — it is the
  ;; design saying a breaking change and its migration are one act.
  (let [live [(on-v2-in-sanctions fixture/ada)]]
    (testing "publish first: refused, because somebody is stranded"
      (is (= :cannot-publish
             (fixture/reason #(registry/publish v1-and-v2 onboarding/v3 live
                                                onboarding/handled-commands)))))
    (testing "migrate first: the instance now points at a version nobody has"
      (let [moved (migration/migrate onboarding/v3-migration
                                     onboarding/v2 onboarding/v3 live)]
        (is (= :unknown-version
               (fixture/reason #(instance/in-flight? (first moved)
                                                     (registry/resolver v1-and-v2)))))))))

(deftest release-publishes-and-moves-in-one-act-test
  (let [live [(on-v2-in-sanctions fixture/ada)]
        {:keys [registry instances]}
        (registry/release v1-and-v2 onboarding/v2 onboarding/v3 onboarding/v3-migration
                          live onboarding/handled-commands)]
    (is (= [1 2 3] (registry/versions registry)))
    (is (= [3] (mapv :definition/version instances)))
    (is (= [:awaiting-screening] (mapv instance/status instances)))
    (testing "and they carry on under the version that now exists"
      (is (= :approved (instance/status
                        (instance/observe (first instances) (registry/resolver registry)
                                          (fixture/event :screening-cleared fixture/day-3))))))))

(deftest release-refuses-without-a-complete-migration-test
  (let [live [(on-v2-in-sanctions fixture/ada)]]
    (is (= :cannot-migrate
           (fixture/reason #(registry/release v1-and-v2 onboarding/v2 onboarding/v3 {}
                                              live onboarding/handled-commands))))))

(deftest release-leaves-finished-instances-where-they-are-test
  ;; Three populations, and only one moves. A finished application is over;
  ;; carrying it onto the new process would imply it is still subject to one.
  (let [live [(on-v2-in-sanctions fixture/ada) (finished fixture/grace)]
        {:keys [instances]}
        (registry/release v1-and-v2 onboarding/v2 onboarding/v3 onboarding/v3-migration
                          live onboarding/handled-commands)
        by-id (into {} (map (juxt :process/id identity)) instances)]
    (is (= 3 (:definition/version (by-id fixture/ada))))
    (is (= 1 (:definition/version (by-id fixture/grace))) "finished, and untouched")
    (is (= :rejected (instance/status (by-id fixture/grace))))))

(deftest release-still-refuses-a-broken-definition-test
  (let [live [(on-v2-in-sanctions fixture/ada)]
        broken (assoc onboarding/v3 :initial :nowhere)]
    (is (= :cannot-publish
           (fixture/reason #(registry/release v1-and-v2 onboarding/v2 broken
                                              onboarding/v3-migration live
                                              onboarding/handled-commands))))))

(deftest an-unknown-version-cannot-be-resolved-test
  (is (= :unknown-version (fixture/reason #(registry/definition-at v1-and-v2 99))))
  (testing "and the latest is the highest published, not the last written"
    (is (= 2 (:process/version (registry/latest v1-and-v2))))))
