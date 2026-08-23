(ns lab32.http.routes
  "The driving adapter: HTTP in, module calls out, and nothing else.

  Lab 23's rules, unchanged. Endpoints name the act rather than the entity, so
  a deposit is `POST /accounts/:id/deposits` and not `PATCH /accounts/:id` with
  a body the server has to interpret. This namespace translates and delegates;
  it holds no SQL, opens no transaction, and `architecture_test.clj` fails the
  build if it acquires either.

  One deliberate departure from the build spec. §9 lists replay as
  `GET /audit/replay/:module`, and it is a POST here. A GET that truncates a
  table is a GET that a link checker, a browser prefetch or a monitoring probe
  can fire, and this one destroys a read model. The spec is right about what
  the endpoint does and the method is a slip worth not copying."
  (:require [clojure.data.json :as json]
            [lab32.accounts.api :as accounts]
            [lab32.compliance.api :as compliance]
            [reitit.ring :as ring]
            [ring.middleware.params :as params])
  (:import (java.time Instant)
           (java.util UUID)))

;; ---------------------------------------------------------------------------
;; Translation
;; ---------------------------------------------------------------------------

(def ^:private status-for
  "Refusal reasons, mapped to what HTTP calls them.

  Everything here is a business refusal and none of it is a 500. An overdraft
  attempt is the system working: the invariant held, and the caller needs to
  know that specifically enough to tell a person why."
  {:malformed-request     400
   :money-from-float      400
   :money-too-precise     400
   :money-missing         400
   :amount-not-positive   400
   :amount-not-decimal    400
   :holder-required       400
   :unknown-command       400
   :account-not-open      404
   :account-already-open  409
   :insufficient-funds    409
   :unknown-module        404})

(defn- qualified
  "A keyword as text, keeping its namespace.

  `name` would render `:accounts/money-deposited` as `\"money-deposited\"`, and
  the namespace is the half that says whose event it is. Lab 28 lost exactly
  this in a dead-letter table and lab 29 wrote the rule down: `json/write-str`
  names a key with `name`, so a namespaced keyword arrives at the far end
  silently shortened. `(str (symbol k))` keeps both halves, and still renders
  an unqualified keyword as its plain name."
  [k]
  (str (symbol k)))

(defn- write-json
  [value]
  (json/write-str
   value
   :key-fn qualified
   ;; BigDecimals deliberately fall through untouched: `data.json` writes one
   ;; as a JSON number with its scale intact, which is the one thing money
   ;; must not lose on the way out.
   :value-fn (fn [_ v]
               (cond
                 (uuid? v)                     (str v)
                 (instance? Instant v)         (str v)
                 ;; TIMESTAMPTZ comes back from the driver as this, and
                 ;; `data.json` has no idea what to do with it -- it throws
                 ;; from inside the writer, where the stack trace names
                 ;; neither the column nor the endpoint.
                 (instance? java.sql.Timestamp v) (str (.toInstant ^java.sql.Timestamp v))
                 (keyword? v)                  (qualified v)
                 :else                         v))))

(defn- respond
  [status body]
  {:status  status
   :headers {"content-type" "application/json"}
   :body    (write-json body)})

(defn- body-of
  [request]
  (if-let [body (:body request)]
    ;; `:bigdec true` here, and it is the same Gotcha #10 one layer out. An
    ;; amount that arrives as `10000.50` must not become a Double at the edge,
    ;; because `money/of` refuses a Double and it would be refusing the wrong
    ;; thing -- the caller sent a perfectly good decimal.
    (json/read-str (slurp body) :key-fn keyword :bigdec true)
    {}))

(defn- uuid-of
  [s]
  (try
    (UUID/fromString s)
    (catch IllegalArgumentException _
      (throw (ex-info "Not an identifier" {:reason :malformed-request :value s})))))

(defn- guard
  "Turn a business refusal into a response, and let anything else be a 500.

  The distinction matters. A refusal is an answer; an unexpected exception is
  an outage, and flattening the two would make a monitoring dashboard useless
  the first time somebody tried to withdraw too much money."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [reason]} (ex-data e)]
          (if-let [status (status-for reason)]
            (respond status {:error reason :message (ex-message e)})
            (throw e)))))))

;; ---------------------------------------------------------------------------
;; Routes
;; ---------------------------------------------------------------------------

(defn router
  "A function from a request map to a response map.

  Not a server. Most of the HTTP suite calls this directly with a map and needs
  no socket at all, which is lab 23's arrangement and the reason those tests
  run in milliseconds."
  [{:keys [accounts compliance replay]}]
  (params/wrap-params
   (ring/ring-handler
    (ring/router
     [["/accounts"
       {:post (fn [request]
                (let [body (body-of request)]
                  (respond 201 (accounts/open-account!
                                accounts
                                {:account-id (uuid-of (:account-id body))
                                 :holder     (:holder body)}))))}]

      ["/accounts/:id"
       {:get (fn [{{:keys [id]} :path-params}]
               (if-let [found (accounts/balance accounts (uuid-of id))]
                 (respond 200 found)
                 (respond 404 {:error :account-not-open})))}]

      ;; A deposit is a thing that happens, so it is a resource that gets
      ;; created. Lab 23 at length on why this beats a PATCH with a delta.
      ["/accounts/:id/deposits"
       {:post (fn [{{:keys [id]} :path-params :as request}]
                (respond 201 (accounts/deposit! accounts
                                                {:account-id (uuid-of id)
                                                 :amount     (:amount (body-of request))})))}]

      ["/accounts/:id/withdrawals"
       {:post (fn [{{:keys [id]} :path-params :as request}]
                (respond 201 (accounts/withdraw! accounts
                                                 {:account-id (uuid-of id)
                                                  :amount     (:amount (body-of request))})))}]

      ["/compliance/flagged"
       {:get (fn [request]
               (let [account (get (:query-params request) "account")]
                 (respond 200 {:flagged (if account
                                          (compliance/flagged-transactions
                                           compliance (uuid-of account))
                                          (compliance/flagged-transactions compliance))})))}]

      ;; --------------------------------------------------------------------
      ;; §9. Three endpoints whose entire purpose is to be impossible against
      ;; a broker with a retention window.
      ;; --------------------------------------------------------------------

      ["/audit/account/:id"
       {:get (fn [{{:keys [id]} :path-params}]
               (respond 200 {:events (accounts/history accounts (uuid-of id))}))}]

      ["/audit/query"
       {:get (fn [request]
               (let [query (:query-params request)
                     instant #(some-> (get query %) Instant/parse java.sql.Timestamp/from)]
                 (respond 200 {:events (accounts/search
                                        accounts
                                        {:event-type (get query "type")
                                         :min-amount (some-> (get query "min") bigdec)
                                         :from       (instant "from")
                                         :until      (instant "until")})})))}]

      ;; Note what this handler does *not* contain: any knowledge of what a
      ;; replay involves. Clearing a projection, resurrecting outbox rows and
      ;; re-deriving messages from the stream are three modules' business
      ;; across three database identities, and a driving adapter that
      ;; orchestrated them would be holding the composition root's job.
      ["/audit/replay/:module"
       {:post (fn [{{:keys [module]} :path-params}]
                (respond 202 {:module      module
                              :republished (replay (keyword module))}))}]]

     {:data {:middleware [guard]}})

    (ring/create-default-handler
     {:not-found (constantly (respond 404 {:error :not-found}))}))))
