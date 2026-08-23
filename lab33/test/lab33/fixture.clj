(ns lab33.fixture
  "Fixed values, so every test is reproducible.

  No `random-uuid`, no `Instant/now`. A lab whose claim is *this answer
  changed and the configuration is why* cannot afford a second reason for an
  answer to change."
  (:import (java.time Instant)))

(defn instant ^Instant [s] (Instant/parse s))

(def january  (instant "2026-01-15T10:00:00Z"))
(def march    (instant "2026-03-15T10:00:00Z"))
(def june     (instant "2026-06-15T10:00:00Z"))
(def december (instant "2026-12-15T10:00:00Z"))

(def event-1 #uuid "00000000-0000-4000-8000-000000000001")
(def event-2 #uuid "00000000-0000-4000-8000-000000000002")
(def event-3 #uuid "00000000-0000-4000-8000-000000000003")

(defn opened
  ([] (opened january))
  ([at] {:event/id event-1 :event/type :account-opened
         :occurred-at at :data {:holder "Ada"}}))

(defn deposited
  ([amount] (deposited amount january))
  ([amount at] {:event/id event-2 :event/type :money-deposited
                :occurred-at at :data {:amount amount}}))

(defn withdrawn
  "A recorded withdrawal, with the fee in `:data` where it belongs and the
  limit that permitted it in `:metadata`."
  ([amount] (withdrawn amount 0M january))
  ([amount fee at]
   {:event/id    event-3
    :event/type  :money-withdrawn
    :occurred-at at
    :data        {:amount amount :fee fee}
    :metadata    {:rules {:overdraft-limit 0M}}}))

(defn reason
  "The `:reason` of a refusal, or `:no-refusal` if the body did not throw.

  Returning a keyword either way keeps the assertions symmetrical — a test
  that says `(is (= :insufficient-funds (reason ...)))` reads the same whether
  it passes or fails."
  [f]
  (try
    (f)
    :no-refusal
    (catch clojure.lang.ExceptionInfo e
      (:reason (ex-data e) :no-reason))))
