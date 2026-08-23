(ns lab34.onboarding
  "The process this lab reconfigures: opening a bank account.

  Chosen because it is the kind of process businesses actually change. A
  transfer's steps are fixed by the mechanics of money; onboarding gains a
  sanctions step when a regulator says so, loses a manual review when the
  vendor improves, and has its escalation window shortened after a complaint.
  Three versions below, each a plausible next quarter.

  It also closes a loop. The process ends by issuing `:open-account` — the
  command [lab 32]'s accounts aggregate already handles — so the process
  manager's output is a request the aggregate validates on its own authority.
  That is lab 10's rule and lab 33's safety argument arriving at the same
  place: a misconfigured process asks for something wrong, and something else
  refuses it."
  (:require [lab34.definition :as definition]))

(def handled-commands
  "What some module has declared it handles. Lab 29's derived routing table,
  reduced to the part this lab needs. `definition/problems` takes it so a
  process cannot issue a command that would arrive nowhere."
  #{:open-account :escalate-review :abandon-application :request-documents})

(def v1
  "Identity, then sanctions, then open the account."
  {:process/name    :onboarding
   :process/version 1
   :initial         :awaiting-identity
   :states
   {:awaiting-identity  {:on      {:identity-verified :awaiting-sanctions
                                   :identity-rejected :rejected}
                         :timeout {:after "PT24H" :issue :escalate-review}}
    :awaiting-sanctions {:on {:sanctions-cleared :approved
                              :sanctions-hit     :rejected}}
    :approved           {:issue :open-account :terminal true}
    :rejected           {:terminal true}}})

(def v2
  "A sanctions hit no longer rejects outright; it goes to a human.

  Additive: v1 declared no `:awaiting-manual`, so no instance can be sitting
  in it, and publishing this strands nobody."
  {:process/name    :onboarding
   :process/version 2
   :initial         :awaiting-identity
   :states
   {:awaiting-identity  {:on      {:identity-verified :awaiting-sanctions
                                   :identity-rejected :rejected}
                         :timeout {:after "PT24H" :issue :escalate-review}}
    :awaiting-sanctions {:on {:sanctions-cleared :approved
                              :sanctions-hit     :awaiting-manual}}
    :awaiting-manual    {:on      {:manually-approved :approved
                                   :manually-rejected :rejected}
                         :timeout {:after "P7D" :issue :abandon-application}}
    :approved           {:issue :open-account :terminal true}
    :rejected           {:terminal true}}})

(def v3
  "The vendor now screens identity and sanctions in one call, so the two steps
  become one.

  Subtractive, and this is the version the registry refuses while anybody is
  still sitting in `:awaiting-sanctions` or `:awaiting-manual`. Those
  instances have to be migrated, deliberately, by somebody who can say where
  they belong."
  {:process/name    :onboarding
   :process/version 3
   :initial         :awaiting-screening
   :states
   {:awaiting-screening {:on      {:screening-cleared :approved
                                   :screening-failed  :rejected}
                         :timeout {:after "PT12H" :issue :escalate-review}}
    :approved           {:issue :open-account :terminal true}
    :rejected           {:terminal true}}})

(def v3-migration
  "Where v2's instances go under v3.

  Not derivable, and note that all three of v2's waiting states collapse into
  one. Somebody who understands the business decided that anybody mid-identity
  or mid-sanctions goes through the combined screen, and that a case already
  with a human goes there too rather than being abandoned. An algorithm could
  have guessed none of that."
  {:awaiting-identity  :awaiting-screening
   :awaiting-sanctions :awaiting-screening
   :awaiting-manual    :awaiting-screening})

(defn check-all!
  "Every version is a process, checked against the commands somebody handles.
  Called by `definition_test` so a typo in the definitions above fails the
  suite rather than the demo."
  []
  (mapv #(definition/check! % handled-commands) [v1 v2 v3]))
