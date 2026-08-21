(ns lab24.authority
  "Which roles may issue which commands. The coarse half of authorisation.

  ADR-0020 in this repository's archive puts it exactly here:

    Coarse-grained gate at the API endpoint and command boundary. A command
    not in the role's permission set is rejected before the FSM or Decider is
    reached. Roles are extracted from the trusted OIDC session claims — never
    from the request body.

  ## Why this is not in `core`

  Because it is the same kind of thing as a Malli schema, and [lab 22](../lab22)
  settled where those go: *a schema describes data crossing a line, and the
  core has no lines.* This table describes who may cross one. It needs no
  state, it can be answered at the door, and it is answered before a command
  object exists — which is [lab 2](../lab2)'s left-hand column exactly.

  The argument against is real and worth stating: \"a depot worker may restock\"
  is a sentence in the ubiquitous language, and banishing domain vocabulary to
  the shell is how anaemic models start. What settles it is the question lab 2
  uses for everything else — *can the answer change without the command
  changing?* It cannot. The same role may always issue the same command types.
  Whether **this** driver may sell from **this** truck can change, and that
  question is in `core/truck.clj`, where state is.

  ## The limitation this gate has by construction

  It only guards the door. `app/react` issues commands from a policy without
  passing through any adapter (lab 10), so nothing here is consulted — and it
  should not be, because a policy is not a person and holds no roles.

  Which means a system relying on edge RBAC *alone* has an unguarded interior.
  That is not a flaw to fix here; it is the reason the second layer exists.
  Ownership lives in `decide`, which nothing can go around.")

(def permissions
  "Role → the command types it may issue."
  {:driver #{:buy-flavour}
   :depot  #{:load-truck :assign-driver}})

(def all-command-types
  (into #{} (mapcat val) permissions))

(defn permits?
  "May any of `roles` issue `command-type`?"
  [roles command-type]
  (contains? (into #{} (mapcat permissions) roles) command-type))

