(ns lab22.adapter.intake
  "The **driving** adapter — the edge lab 21 did not have.

  Lab 21 built driven adapters: a store, an outbox, a clock. Things the
  application reaches out to. Nothing reached *in*, so commands arrived in
  tests as literal maps and were trusted.

  This is the other side of the hexagon: where a message from outside becomes
  a command, or is refused. [Lab 2](../lab2) said exactly where that belongs —
  at the adapter, *before the command object is even constructed*.

  Which is also why `app.clj` still contains no `if`. Rejecting a malformed
  message happens out here, and the application layer only ever sees commands
  that are already well-formed."
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
  (let [command (->command ids message)]
    (if-let [problems (schema/validate command)]
      {:rejected :malformed :because problems}
      (try
        {:accepted (app/handle deps truck-id command)}
        (catch clojure.lang.ExceptionInfo e
          {:rejected :refused :because (ex-message e) :data (ex-data e)})))))
