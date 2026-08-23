(ns lab32.migrate-test
  "The statement splitter.

  Labs 19 to 30 split their schema file on `;` and got away with it, because
  none of them contained a PL/pgSQL function. Phase 2's NOTIFY trigger does,
  and `PERFORM pg_notify('outbox_events', '');` inside a `$$ ... $$` body is
  exactly the case a naive split cuts in half."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lab32.db.migrate :as migrate]))

(deftest ordinary-statements-split-on-semicolons-test
  (is (= ["SELECT 1" "SELECT 2"] (migrate/statements "SELECT 1; SELECT 2;")))
  (testing "a trailing statement without a semicolon still counts"
    (is (= ["SELECT 1" "SELECT 2"] (migrate/statements "SELECT 1; SELECT 2"))))
  (testing "blank statements are dropped"
    (is (= ["SELECT 1"] (migrate/statements ";;  SELECT 1 ;; \n ;")))))

(deftest a-semicolon-inside-a-dollar-quoted-body-is-not-a-terminator-test
  (let [sql "CREATE FUNCTION f() RETURNS trigger LANGUAGE plpgsql AS $$
             BEGIN
               PERFORM pg_notify('outbox_events', '');
               RETURN NULL;
             END;
             $$;
             CREATE TRIGGER t AFTER INSERT ON x EXECUTE FUNCTION f();"
        found (migrate/statements sql)]
    (is (= 2 (count found)) "the function body was cut into pieces")
    (is (re-find #"RETURN NULL" (first found)))
    (is (re-find #"CREATE TRIGGER" (second found)))))

(deftest a-named-dollar-tag-is-honoured-test
  (let [found (migrate/statements "DO $body$ BEGIN; END; $body$; SELECT 1;")]
    (is (= 2 (count found)))
    (is (re-find #"BEGIN; END;" (first found)))))

(deftest a-dollar-sign-that-is-not-a-tag-does-not-open-one-test
  ;; `$1` is a positional parameter, not a quote. Treating it as one would
  ;; swallow the rest of the file.
  (is (= 2 (count (migrate/statements "SELECT $1; SELECT 2;")))))

(deftest a-semicolon-in-a-string-literal-is-not-a-terminator-test
  (is (= ["SELECT 'a;b'" "SELECT 2"]
         (migrate/statements "SELECT 'a;b'; SELECT 2;")))
  (testing "and an escaped quote does not end the string early"
    (is (= 1 (count (migrate/statements "SELECT 'it''s; fine';"))))))

(deftest a-semicolon-in-a-comment-is-not-a-terminator-test
  (is (= 1 (count (migrate/statements "-- a comment; with a semicolon\nSELECT 1;"))))
  (testing "and the comment does not swallow the statement after it"
    ;; The comment stays attached to the statement it precedes, which is
    ;; deliberate: Postgres ignores it, and keeping it means a syntax error
    ;; reports the text a person actually wrote.
    (let [[only] (migrate/statements "-- nope;\nSELECT 1;")]
      (is (str/includes? only "SELECT 1"))
      (is (str/starts-with? only "-- nope")))))

(deftest every-migration-in-the-lab-splits-into-runnable-statements-test
  ;; A guard against the splitter and the files drifting apart: each migration
  ;; must produce at least one statement and none of them may end up empty.
  (doseq [filename ["001_schemas.sql" "002_accounts_event_stream.sql"
                    "003_messaging_outbox.sql" "004_compliance_inbox.sql"
                    "005_compliance_read_model.sql"]]
    (let [found (migrate/statements (slurp (io/resource (str "migrations/" filename))))]
      (is (seq found) (str filename " produced no statements"))
      (is (every? seq found) (str filename " produced an empty statement")))))
