(ns lab20.relay
  "Moving messages from one module's outbox to another's inbox.

  Two ways to do it, and the difference is the whole lab.

  Across a network the relay must publish and *then* record that it did, in
  two operations that cannot share a transaction. Whichever order you choose,
  a crash between them costs you something.

  Inside one database it need not. The outbox row and the inbox row are two
  rows in one schema, and one transaction covers both."
  (:require [lab20.inbox :as inbox]
            [lab20.outbox :as outbox]
            [next.jdbc :as jdbc]))

;; ---------------------------------------------------------------------------
;; The distributed case: publish, then mark. At-least-once, unavoidably.
;; ---------------------------------------------------------------------------

(defn relay-across-a-boundary!
  "Publish each pending message with `publish!`, then mark it sent.

  Those are two writes to two systems. `crash-after-publish?` lets a test stop
  between them, which is the only interesting moment in the whole pattern.

  Publish-then-mark loses nothing and may repeat. Mark-then-publish repeats
  nothing and may lose. There is no third option, which is why the recipient
  needs an inbox."
  ([ds publish!] (relay-across-a-boundary! ds publish! (constantly false)))
  ([ds publish! crash-after-publish?]
   (reduce (fn [sent row]
             (publish! row)
             (if (crash-after-publish? row)
               (reduced (conj sent (:message-id row)))
               (do (jdbc/with-transaction [tx ds]
                     (outbox/mark-sent! tx (:id row)))
                   (conj sent (:message-id row)))))
           []
           (outbox/pending ds))))

;; ---------------------------------------------------------------------------
;; The modular-monolith case: one database, one transaction.
;;
;; One database can make the inbox claim, a local database effect and the
;; outbox mark atomic. This is exactly-once local effect, not a general claim
;; about delivery or remote side effects.
;; ---------------------------------------------------------------------------

(defn relay-within-one-database!
  "Move every pending message into its recipient's inbox, transactionally.

  `effects` maps a recipient to a function of `[tx message]`, run in the same
  transaction as its inbox row and the outbox row's `sent_at`."
  [ds effects]
  (let [pending (outbox/pending ds)
        missing (->> pending
                     (map (comp keyword :recipient))
                     (remove #(contains? effects %))
                     distinct
                     seq)]
    (when missing
      (throw (ex-info "No handler for recipient" {:recipients missing})))
    (reduce (fn [moved row]
              (jdbc/with-transaction [tx ds]
                (let [recipient (:recipient row)
                      fact-id   (parse-uuid (get-in row [:payload :fact-id]))
                      effect!   (get effects (keyword recipient))
                      result    (inbox/handle-once-in-transaction!
                                 tx recipient fact-id #(effect! % row))]
                  (outbox/mark-sent! tx (:id row))
                  (conj moved [(:message-id row) result]))))
            []
            pending)))
