(ns lab17.snapshot
  "Snapshots: a cache of a fold, kept beside the log.

  Everything here is an optimisation. Delete the lot and every answer is
  unchanged — only the work goes up. That property is what makes a snapshot
  safe to store, safe to lose, and safe to get wrong: the events are still
  there, so it can always be recomputed exactly.

  Which makes it an unusual cache. Most caches risk being stale in ways you
  cannot detect. This one can only be stale in ways you can."
  (:require [lab17.store :as store]))

(def none {})

;; ---------------------------------------------------------------------------
;; A snapshot records three things, and the third is the one people leave out.
;;
;;   :state          the folded result
;;   :version        the stream version it was folded up to
;;   :fold-version   which shape of `evolve` produced it
;; ---------------------------------------------------------------------------

(defn take-snapshot
  "Fold the stream and keep the result."
  [snapshots log stream-id {:keys [replay fold-version]}]
  (let [events (store/stream log stream-id)]
    (assoc snapshots stream-id
           {:state        (replay events)
            :version      (store/current-version log stream-id)
            :fold-version fold-version})))

(defn discard
  [snapshots stream-id]
  (dissoc snapshots stream-id))

(defn usable?
  "A snapshot is usable only if the fold that produced it is the fold about to
  continue it.

  Change `evolve`'s state shape and every stored snapshot becomes wrong,
  though not one event changed. There is no upcaster for this — a snapshot is
  derived, so the answer is always to throw it away and fold again."
  [snapshot fold-version]
  (and snapshot (= fold-version (:fold-version snapshot))))

;; ---------------------------------------------------------------------------
;; Loading.
;;
;; Read the snapshot FIRST, then the events strictly after its version. The
;; other order folds events the snapshot already contains, and produces a
;; number rather than an error.
;; ---------------------------------------------------------------------------

(defn- events-after
  [log stream-id version]
  (->> (store/stream log stream-id)
       (filter #(> (:stream/version %) version))
       vec))

(defn load-state
  "The current state of a stream, using a snapshot when one is usable.

  Returns `{:state … :folded n :from-snapshot? bool}` — `:folded` being the
  number of events this call had to fold, which is the whole point."
  [snapshots log stream-id {:keys [evolve replay fold-version]}]
  (let [snapshot (get snapshots stream-id)]
    (if (usable? snapshot fold-version)
      (let [remaining (events-after log stream-id (:version snapshot))]
        {:state          (reduce evolve (:state snapshot) remaining)
         :folded         (count remaining)
         :from-snapshot? true})
      (let [events (store/stream log stream-id)]
        {:state          (replay events)
         :folded         (count events)
         :from-snapshot? false}))))

;; ---------------------------------------------------------------------------
;; When to take one.
;;
;; Too often is write amplification for no gain; too rarely is a long fold on
;; every read. And because the result is derived, taking one can fail, lag, or
;; be skipped entirely without losing anything — so it belongs off the append's
;; critical path, not inside it.
;; ---------------------------------------------------------------------------

(def every-n-events 10)

(defn due?
  [snapshots log stream-id]
  (let [version (store/current-version log stream-id)
        taken   (get-in snapshots [stream-id :version] 0)]
    (>= (- version taken) every-n-events)))
