(ns lab13.upcast
  "The version ladder: old shapes in, current shape out.

  Everything downstream of `read-event` sees one shape. That is the whole
  design — `evolve` and `decide` never learn that there was ever another one,
  because a version branch in the domain is permanent.")

;; ---------------------------------------------------------------------------
;; Versions are per type, not per system.
;;
;; This was a single number until `:flavour-sold` reached v4 and
;; `:flavour-sold-gross` reached v2. One global counter would have to claim
;; that a v2 gross event is three versions behind, which is not a fact about
;; anything — the two types have never shared a schema and their ladders are
;; the same length only by coincidence.
;; ---------------------------------------------------------------------------

(def current-version
  {:flavour-sold       4
   :flavour-sold-gross 2})

(defn current-version-of [event-type] (get current-version event-type 1))

(def vat-rate 0.20M)

;; ---------------------------------------------------------------------------
;; :price/unknown
;;
;; v1 events have no price, and none can be invented. The truck did not record
;; one; no amount of code makes it true after the fact.
;;
;; Filling in a plausible number here would be forging history, and it would
;; be invisible: the fold would produce a total that looks right. An explicit
;; marker forces every reader to decide what to do about it, which is the
;; correct amount of friction.
;; ---------------------------------------------------------------------------

(def unknown :price/unknown)

(defn unknown-price? [x] (= unknown x))

;; ---------------------------------------------------------------------------
;; One step per version, and each step is small.
;;
;; Chaining is the point: v1 reaches v3 by going through v2, so adding a
;; fourth version means writing one function rather than revisiting three.
;; ---------------------------------------------------------------------------

(defmulti upcast-step
  "Raise an event from version n to version n+1."
  (fn [event] [(:event/type event) (get-in event [:metadata :schema-version])]))

(defmethod upcast-step [:flavour-sold 1]
  [event]
  ;; The new field has no historical value. Say so, rather than guess.
  (-> event
      (assoc-in [:data :price] unknown)
      (assoc-in [:metadata :schema-version] 2)))

(defmethod upcast-step [:flavour-sold 2]
  [event]
  ;; A rename. The fact is unchanged; only its encoding moved.
  (-> event
      (update :data #(-> % (assoc :unit-price (:price %)) (dissoc :price)))
      (assoc-in [:metadata :schema-version] 3)))

;; ---------------------------------------------------------------------------
;; v3 → v4, and gross v1 → v2: a keyword becomes a string.
;;
;; The cheapest kind of step — same fact, different encoding, nothing to
;; invent and nothing that could be wrong. Compare the v1 → v2 step above,
;; which had to admit it did not know something.
;;
;; `name` is used rather than `str` deliberately: on a keyword it strips the
;; colon, and on a string it is the identity, so a step that somehow ran twice
;; would still be harmless. The version bump is what actually stops the loop;
;; this is belt and braces on a function that rewrites history on read.
;; ---------------------------------------------------------------------------

(defmethod upcast-step [:flavour-sold 3]
  [event]
  (-> event
      (update-in [:data :flavour] name)
      (assoc-in [:metadata :schema-version] 4)))

(defmethod upcast-step [:flavour-sold-gross 1]
  [event]
  (-> event
      (update-in [:data :flavour] name)
      (assoc-in [:metadata :schema-version] 2)))

(defn read-event
  "Walk an event up the ladder to the current version.

  Upcasting happens on **read**. Nothing is written back: the stored event is
  a fact, and rewriting it to a newer shape is the last-resort migration that
  costs you the audit trail."
  [event]
  (loop [event event, steps 0]
    (let [version (get-in event [:metadata :schema-version] 1)]
      (cond
        ;; Types that were never versioned past 1 are already current.
        (nil? (get-method upcast-step [(:event/type event) version])) event
        (> steps 10) (throw (ex-info "Upcaster chain did not terminate"
                                     {:event/type (:event/type event)
                                      :schema-version version}))
        :else (recur (upcast-step event) (inc steps))))))

(defn read-all
  [events]
  (mapv read-event events))
