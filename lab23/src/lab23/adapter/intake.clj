(ns lab23.adapter.intake
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
  (:require [lab23.app :as app]
            [lab23.port.driven :as driven]
            [lab23.schema.command :as schema]))

(defn- ->command
  "Turn an outside message into a command. No domain knowledge; just naming.

  What arrives may be wire-shaped — a JSON body has no keywords — so `submit`
  decodes it with the schema before validating. That is lab 22's technique
  pointed inward: the same loss the store inflicts, the same schema fixing it."
  [ids {:keys [type data]}]
  {:command/id   (driven/new-id ids)
   :command/type type
   :data         data})

(defn submit
  "Accept a message from outside, or say why not.

  Returns `{:accepted events}` or `{:rejected explanation}`.

  Two rejections are possible here and they are not the same:

    :malformed   the message is not a well-formed command       — this file
    :refused     it is well-formed and the domain says no       — `decide`
    :conflict    the stream moved while we were deciding        — the store

  The first two are lab 2's columns. The third is lab 7's optimistic
  concurrency arriving at the edge, and it is worth keeping separate because
  it is the only one of the three where retrying the *identical* message is
  the correct response."
  [{:keys [ids] :as deps} truck-id message]
  (let [command (schema/decode (->command ids message))]
    (if-let [problems (schema/validate command)]
      {:rejected :malformed :because problems}
      (try
        {:accepted (app/handle deps truck-id command)}
        (catch clojure.lang.ExceptionInfo e
          {:rejected (if (= "Concurrent modification of stream" (ex-message e))
                       :conflict
                       :refused)
           :because  (ex-message e)
           :data     (ex-data e)})))))
