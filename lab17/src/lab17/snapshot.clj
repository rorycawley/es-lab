(ns lab17.snapshot
  "Snapshots: a cache of a fold, kept beside the log.

  Everything here is an optimisation. Delete the lot and every answer is
  unchanged — only the work goes up. The events remain the source of record,
  so a missing, obsolete or rejected snapshot can be rebuilt.

  That does not make arbitrary corruption detectable. The envelope checks
  fold compatibility and stream position; storage integrity needs its own
  checksum or validation."
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
  "Fold one stream read and keep its state and version as one coherent value."
  [snapshots log stream-id {:keys [replay fold-version]}]
  (let [events  (store/stream log stream-id)
        version (or (:stream/version (peek events)) 0)]
    (assoc snapshots stream-id
           {:state        (replay events)
            :version      version
            :fold-version fold-version})))

(defn discard
  [snapshots stream-id]
  (dissoc snapshots stream-id))

(defn usable?
  "Is this a compatible snapshot position for the current stream?

  An incompatible change to `evolve` can make stored state wrong although no
  event changed. A position beyond the stream is also unsafe: trusting it
  would skip events. Both cases are cheap to reject and rebuild."
  [snapshot fold-version current-version]
  (let [version (:version snapshot)]
    (and (map? snapshot)
         (contains? snapshot :state)
         (contains? snapshot :fold-version)
         (= fold-version (:fold-version snapshot))
         (integer? version)
         (<= 0 version current-version))))

;; ---------------------------------------------------------------------------
;; Loading.
;;
;; Read the snapshot FIRST, then the events strictly after its version. The
;; other order folds events the snapshot already contains, and produces a
;; number rather than an error.
;; ---------------------------------------------------------------------------

(defn- events-after
  [events version]
  (->> events
       (filter #(> (:stream/version %) version))
       vec))

(defn load-state
  "The current state of a stream, using a snapshot when one is usable.

  Returns `{:state … :folded n :from-snapshot? bool}` — `:folded` being the
  number of events this call had to fold, which is the whole point."
  [snapshots log stream-id {:keys [evolve replay fold-version]}]
  (let [snapshot        (get snapshots stream-id)
        events          (store/stream log stream-id)
        current-version (or (:stream/version (peek events)) 0)]
    (if (usable? snapshot fold-version current-version)
      (let [remaining (events-after events (:version snapshot))]
        {:state          (reduce evolve (:state snapshot) remaining)
         :folded         (count remaining)
         :from-snapshot? true})
      {:state          (replay events)
       :folded         (count events)
       :from-snapshot? false})))

;; ---------------------------------------------------------------------------
;; When to take one.
;;
;; Too often is write amplification for no gain; too rarely is a long fold on
;; every read. Because the result is derived, taking one can usually fail, lag,
;; or be skipped without losing authoritative facts. Keeping it off the append
;; path avoids coupling a real write to a cache; doing otherwise is a deliberate
;; transactional trade-off.
;; ---------------------------------------------------------------------------

(def every-n-events 10)

(defn due?
  "Should the optional snapshot worker refresh this stream?

  A compatible snapshot follows the every-N policy. An incompatible snapshot
  is due immediately so reads do not keep paying for a full replay."
  [snapshots log stream-id fold-version]
  (let [events          (store/stream log stream-id)
        current-version (or (:stream/version (peek events)) 0)
        existing        (get snapshots stream-id)]
    (if existing
      (or (not (usable? existing fold-version current-version))
          (>= (- current-version (:version existing)) every-n-events))
      (>= current-version every-n-events))))
