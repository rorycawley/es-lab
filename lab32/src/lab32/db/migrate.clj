(ns lab32.db.migrate
  "Numbered SQL files, applied once, in order. No Flyway.

  A migration tool is a reasonable thing to depend on and this lab does not,
  for the same reason it does not depend on a broker: the argument being made
  is that Postgres already does this, and importing a library to demonstrate
  that would be a strange way to make the point. Sixty lines is the whole cost.

  What this deliberately does *not* have: down-migrations, checksums, a repair
  command, or out-of-order tolerance. Those are the features you need on a
  system with a history, and adding them here would be building the thing the
  lab just said you did not need."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]))

(def ^:private ledger
  "Applied migrations, in `public` because it is the one schema that exists
  before migration 001 has run."
  "CREATE TABLE IF NOT EXISTS public.schema_migrations (
     filename    TEXT PRIMARY KEY,
     applied_at  TIMESTAMPTZ NOT NULL DEFAULT now()
   )")

;; ---------------------------------------------------------------------------
;; Splitting SQL into statements
;;
;; JDBC will not execute two statements in one call, so the file has to be cut
;; up, and `(str/split sql #";")` -- which is what labs 19 to 30 did -- is
;; wrong the moment a file contains a PL/pgSQL function. Migration 006 defines
;; the NOTIFY trigger, whose body is `$$ ... PERFORM pg_notify(...); ... $$`,
;; and a naive split cuts it in half and produces two syntax errors.
;;
;; So this is a small state machine rather than a regex: it knows that a
;; semicolon inside a dollar-quoted block, a string literal or a line comment
;; is not a statement terminator.
;; ---------------------------------------------------------------------------

(defn- dollar-tag-at
  "The dollar-quote tag opening at index `i`, or nil.

  Both `$$` and `$body$` are tags; `$1` is a parameter and is not."
  [^String s i]
  (when (= \$ (.charAt s i))
    (let [n (.length s)]
      (loop [j (inc i)]
        (cond
          (>= j n)                    nil
          (= \$ (.charAt s j))        (subs s i (inc j))
          (or (Character/isLetterOrDigit (.charAt s j))
              (= \_ (.charAt s j)))   (recur (inc j))
          :else                       nil)))))

(defn statements
  "Cut a migration file into executable statements."
  [^String sql]
  (let [n (.length sql)]
    (loop [i 0, start 0, state :normal, tag nil, found []]
      (if (>= i n)
        (let [tail (str/trim (subs sql start))]
          (cond-> found (not (str/blank? tail)) (conj tail)))
        (let [c (.charAt sql i)]
          (case state
            :normal
            (cond
              (= \; c)
              (let [statement (str/trim (subs sql start i))]
                (recur (inc i) (inc i) :normal nil
                       (cond-> found (not (str/blank? statement)) (conj statement))))

              (and (= \- c) (< (inc i) n) (= \- (.charAt sql (inc i))))
              (recur (+ i 2) start :line-comment nil found)

              (= \' c)
              (recur (inc i) start :string nil found)

              :else
              (if-let [opening (dollar-tag-at sql i)]
                (recur (+ i (count opening)) start :dollar opening found)
                (recur (inc i) start :normal nil found)))

            :line-comment
            (recur (inc i) start (if (= \newline c) :normal :line-comment) nil found)

            :string
            ;; `''` is an escaped quote. Leaving on the first and re-entering on
            ;; the second lands in the same place, so it needs no special case.
            (recur (inc i) start (if (= \' c) :normal :string) nil found)

            :dollar
            (if (and (= \$ c)
                     (.startsWith (subs sql i) ^String tag))
              (recur (+ i (count tag)) start :normal nil found)
              (recur (inc i) start :dollar tag found))))))))

;; ---------------------------------------------------------------------------

(def ^:private files
  "The migrations, in order.

  Named rather than discovered. Listing a classpath directory works when the
  classpath entry is a directory, which is how every lab runs, and silently
  finds nothing inside a jar -- a failure mode that would show up as a system
  that starts happily against an empty database. An explicit vector cannot do
  that, and the cost is one line per migration."
  ["001_schemas.sql"
   "002_accounts_event_stream.sql"
   "003_messaging_outbox.sql"
   "004_compliance_inbox.sql"
   "005_compliance_read_model.sql"
   "006_outbox_notify_trigger.sql"])

(defn- applied
  [datasource]
  (into #{}
        (map :schema_migrations/filename)
        (jdbc/execute! datasource ["SELECT filename FROM public.schema_migrations"])))

(defn- apply-file!
  [datasource filename]
  ;; One transaction per file, and one connection: `SET ROLE` is session state,
  ;; so running each statement against the datasource would take a fresh
  ;; connection from the pool and leave the tables owned by the migration user
  ;; instead of the module. Lab 29 found this the hard way.
  (jdbc/with-transaction [tx datasource]
    (doseq [statement (statements (slurp (io/resource (str "migrations/" filename))))]
      (jdbc/execute! tx [statement]))
    (jdbc/execute! tx ["INSERT INTO public.schema_migrations (filename) VALUES (?)"
                       filename])))

(defn migrate!
  "Bring the database up to date. Returns the filenames applied by this call.

  Idempotent, which matters more than usual here: the suite starts and stops
  many systems against one container, and every start runs this."
  [datasource]
  (jdbc/execute! datasource [ledger])
  (let [done (applied datasource)
        todo (remove done files)]
    (doseq [filename todo]
      (log/info "applying migration" filename)
      (apply-file! datasource filename))
    (vec todo)))
