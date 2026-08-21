(ns lab22.adapter.intake
  "A **driving** adapter for untrusted input.

  Lab 21 already had driving adapters: tests and the demo called the
  application's use-case functions. Their command maps were trusted, however;
  there was no adapter translating input from outside the process.

  This is the other side of the hexagon: where a message from outside becomes
  a command, or is refused. [Lab 2](../lab2) said exactly where that belongs —
  at the adapter, *before the command object is even constructed*.

  Which is also why `app.clj` still contains no `if`. Rejecting a malformed
  message happens out here, before an id is allocated or an internal command
  is constructed. The application layer only sees well-formed commands."
  (:require [lab22.app :as app]
            [lab22.port :as port]
            [lab22.schema.command :as schema]))

(defn- ->command
  "Turn an outside message into a command. No domain knowledge; just naming."
  [ids {:keys [type data]}]
  {:command/id   (port/new-id ids)
   :command/type type
   :data         data})

(defn submit
  "Accept a message from outside, or say why not.

  Returns `{:accepted events}` or `{:rejected explanation}`.

  Two rejections are possible here and they are not the same:

    :malformed   the message is not a well-formed command       — this file
    :refused     it is well-formed and the domain says no       — `decide`

  Lab 2's two columns, and the whole reason they are two."
  [{:keys [ids] :as deps} truck-id message]
  (if-let [problems (schema/validate-message message)]
    {:rejected :malformed :because problems}
    (let [command (->command ids message)]
      (try
        {:accepted (app/handle deps truck-id command)}
        (catch clojure.lang.ExceptionInfo e
          {:rejected :refused :because (ex-message e) :data (ex-data e)})))))
