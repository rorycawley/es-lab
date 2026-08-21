(ns lab24.adapter.intake
  "The **driving** adapter — where a message from outside becomes a command,
  or is refused.

  Three gates, in this order, and the order is the design:

    1. may this caller issue this KIND of command?   `authority`   — roles
    2. is this a well-formed command at all?         `schema`      — shape
    3. should it happen, given what is true?         `decide`      — state

  Each needs strictly more application context than the last. Authentication
  already established the roles, so gate 1 needs no body or stream. Gate 2
  needs the message. Gate 3 needs the stream.

  Authorisation is deliberately *first*, ahead of validation, which is not the
  obvious order. A caller with no permission should not be able to map your
  schema by watching which malformed bodies come back 400 — and it costs
  nothing to check, because the command *type* comes from the route rather
  than the body. There is no need to parse a message to know what it claims to
  be.

  The raw external message is validated before internal identity is allocated;
  the actor is then copied only from the verified principal."
  (:require [lab24.app :as app]
            [lab24.authority :as authority]
            [lab24.port.driven :as driven]
            [lab24.schema.command :as schema]))

(defn- ->command
  "Turn an outside message into a command. No domain knowledge; just naming.

  Note where `:command/actor` comes from — the verified principal, passed in
  as an argument. It is never read from `message`, and there is no branch here
  that could make it so. That is the enforcement of ADR-0020's rule that roles
  and identity come from the token and never from the body: not a check, but
  the absence of a path."
  [ids principal {:keys [type data]}]
  {:command/id    (driven/new-id ids)
   :command/type  type
   :correlation-id (driven/new-id ids)
   :command/actor (:actor principal)
   :data          data})

(defn submit
  "Accept a message from outside, or say why not.

  Returns `{:accepted events}` or `{:rejected kind :because …}`, where the
  kinds are the ones lab 2 started and this lab finishes:

    :forbidden   authenticated, and not allowed to ask       — `authority`
    :malformed   not a well-formed command                   — `schema`
    :refused     well-formed, permitted, and the domain says no — `decide`

  `:forbidden` arrives twice over, from opposite ends: from the role gate
  below, and from `decide` when a driver reaches for a truck that is not
  theirs. Same answer to the client, two different questions — which is the
  whole reason authorisation needed splitting."
  [{:keys [ids] :as deps} truck-id principal message]
  (if-not (authority/permits? (:roles principal) (:type message))
    {:rejected :forbidden
     :because  "your role does not permit this command"}
    (if-let [problems (schema/validate-message message)]
      {:rejected :malformed :because problems}
      (let [command (->command ids principal message)]
        (try
          {:accepted (app/handle deps truck-id command)}
          (catch clojure.lang.ExceptionInfo failure
            (case (:reason (ex-data failure))
              :not-authorised {:rejected :forbidden
                               :because (ex-message failure)
                               :data (ex-data failure)}
              :sold-out {:rejected :refused
                         :because (ex-message failure)
                         :data (ex-data failure)}
              (throw failure))))))))
