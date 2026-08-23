(ns lab34.engine.hardcoded
  "The same onboarding process, written the way lab 11 wrote its transfer.

  Lab 0's move again: the counter-example is built properly, because an
  argument against something is only worth having if you can run it.

  And there is nothing wrong with this code. It is shorter than the definition
  it replaces, a reader can follow it top to bottom, and for a process that
  never changes it is the better choice. The lab is not claiming otherwise.

  What it cannot do is answer three questions, and `contrast_test.clj` asks
  them:

    what version is this instance running?    there is no version
    what steps does the process have?         `grep`, and hope
    can I add a step without touching         no — it is a deploy, and every
    instances already running?                in-flight instance changes
                                              underneath itself at once

  That last one is the whole difference. Changing this function changes the
  behaviour of every instance that has already started, retroactively and
  invisibly, because the state each one is sitting in is re-interpreted by
  whatever code happens to be deployed when it next wakes up."
  (:import (java.time Duration Instant)))

(def escalate-after (Duration/ofHours 24))

(defn advance
  "state -> event -> state"
  [status {:keys [event/type]}]
  (case [status type]
    [:awaiting-identity  :identity-verified]  :awaiting-sanctions
    [:awaiting-identity  :identity-rejected]  :rejected
    [:awaiting-sanctions :sanctions-cleared]  :approved
    [:awaiting-sanctions :sanctions-hit]      :awaiting-manual
    [:awaiting-manual    :manually-approved]  :approved
    [:awaiting-manual    :manually-rejected]  :rejected
    status))

(defn issues
  "state -> now -> entered-at -> [command]"
  [status ^Instant now ^Instant entered-at]
  (cond
    (= :approved status)
    [:open-account]

    (and (= :awaiting-identity status)
         entered-at
         (not (.isBefore now (.plus entered-at escalate-after))))
    [:escalate-review]

    :else []))
