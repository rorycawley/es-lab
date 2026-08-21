(ns lab24.adapter.http
  "The HTTP **driving** adapter — and the only namespace in this lab that may
  require ring or reitit.

  Everything below it never learns HTTP exists. `intake`, `app`, `core`: not
  one of them can tell whether a command arrived over a socket, off a queue,
  or from a test calling a function. A fitness test asserts it.

  This lab adds authentication in front, as middleware, because it applies to
  every request identically and before anything domain-shaped is in scope.
  What it produces — a principal — is passed *onward as an argument*. Nothing
  downstream reads it from the request, and nothing downstream could."
  (:require [lab24.adapter.auth :as auth]
            [lab24.adapter.intake :as intake]
            [lab24.adapter.wire :as wire]
            [lab24.app :as app]
            [reitit.ring :as ring]))

;; ---------------------------------------------------------------------------
;; Status codes: lab 2's two columns, now with the two that authentication and
;; authorisation add.
;;
;;   400  malformed        the schema refused it; the domain never saw it
;;   401  unauthenticated  I do not know who you are          — the middleware
;;   403  forbidden        I know, and no                     — roles, or `decide`
;;   409  conflict         the stream moved under you
;;   422  refused          well-formed, permitted, and the domain said no
;;
;; 401 and 403 are the pair most often collapsed. A refreshed credential may
;; repair an expired-token 401. Replaying the same valid credential cannot
;; repair a 403; a genuinely different principal or grant might.
;;
;; 401 does not appear in this table because it is never a domain outcome. It
;; is decided before any of this runs, by `require-authentication`.
;; ---------------------------------------------------------------------------

(def ^:private status-for
  {:malformed 400
   :forbidden 403
   :refused   422})

(defn- outcome->response
  [{:keys [accepted rejected because]}]
  (if accepted
    ;; A command returns the FACTS, not the resource.
    (wire/respond 200 {:recorded (mapv (fn [e] {:type    (name (:event/type e))
                                                :version (:stream/version e)
                                                :data    (:data e)})
                                       accepted)})
    (if-let [status (status-for rejected)]
      (wire/respond status
                    {:error rejected :detail (if (map? because) because (str because))})
      (throw (ex-info "Unknown intake outcome" {:rejected rejected :because because})))))

;; ---------------------------------------------------------------------------
;; Routes.
;;
;; The path names the act, Stripe-style (lab 23). Adding authentication does
;; not change that: `/v1/driver-assignments` is a thing that happened, and
;; assigning a driver is as much an act as selling a cone.
;; ---------------------------------------------------------------------------

(def ^:private truck-id #uuid "0f1c2b3a-0000-4000-8000-000000000001")

(defn- command-route
  [deps command-type]
  (fn [request]
    (try
      (outcome->response
       (intake/submit deps truck-id (auth/principal request)
                      {:type command-type :data (wire/read-body request)}))
      (catch clojure.lang.ExceptionInfo failure
        (case (:reason (ex-data failure))
          :malformed-json (wire/respond 400 {:error :malformed
                                             :detail "Request body is not valid JSON"})
          :concurrent-modification (wire/respond 409 {:error :conflict
                                                      :detail "Stream changed; read and retry"})
          (throw failure))))))

(defn- stock-route
  "ADR-0020's third layer: the same underlying data, projected differently per
  role. A depot user sees who is driving; a driver sees the stock they are
  selling and nothing about the roster.

  Both projections are the core's — `truck/stock` and `truck/operations`.
  Only the *choice* is here, which is what ADR-0020 means by \"applied in query
  adapters, not in the domain\"."
  [deps]
  (fn [request]
    (let [roles (:roles (auth/principal request))]
      (if (contains? roles :depot)
        (wire/respond 200 (app/operations deps truck-id))
        (wire/respond 200 {:stock (app/stock deps truck-id)})))))

(defn routes
  "The API surface. A test asserts this table and the command registry are the
  same list — an endpoint with no command, or a command with no endpoint, both
  fail the build."
  [deps]
  [;; Unauthenticated on purpose. A load balancer has no token, and an
   ;; endpoint that reveals only \"this process is up\" reveals nothing.
   ["/health" {:get {:handler (fn [_] (wire/respond 200 {:status "ok"}))}}]

   ["/v1"
    ;; Applied to the whole subtree rather than to each route, because the
    ;; failure mode of per-route authentication is an endpoint somebody forgot.
    {:middleware [[auth/authenticate deps]
                  auth/require-authentication]}

    ["/sales"              {:post {:command :buy-flavour
                                   :handler (command-route deps :buy-flavour)}}]
    ["/restocks"           {:post {:command :load-truck
                                   :handler (command-route deps :load-truck)}}]
    ["/replenishments"     {:post {:command :ensure-stock
                                   :handler (command-route deps :ensure-stock)}}]
    ["/driver-assignments" {:post {:command :assign-driver
                                   :handler (command-route deps :assign-driver)}}]
    ["/stock"              {:get  {:handler (stock-route deps)}}]]])

(defn handler
  "A function from a request map to a response map. That is all a web layer is."
  [deps]
  (ring/ring-handler
   (ring/router (routes deps))
   (ring/create-default-handler
    {:not-found (fn [_] (wire/respond 404 {:error :no-such-endpoint}))})))
