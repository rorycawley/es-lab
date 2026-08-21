(ns lab23.adapter.http
  "The HTTP **driving** adapter — and the only namespace in this lab that may
  require ring or reitit.

  Everything below it never learns HTTP exists. `intake`, `app`, `core`: not
  one of them can tell whether a command arrived over a socket, off a queue,
  or from a test calling a function. A fitness test asserts it.

  Note also what a ring handler *is*: a function from a map to a map. So this
  whole adapter is testable by calling it with a map — no port, no server, no
  client. Exactly one test starts Jetty, to prove the socket is wired. If
  testing your web layer needs a running server, the layer is doing too much."
  (:require [clojure.data.json :as json]
            [lab23.adapter.intake :as intake]
            [lab23.app :as app]
            [reitit.ring :as ring]))

;; ---------------------------------------------------------------------------
;; The wire boundary, in plain sight.
;;
;; Labs 19, 20 and 22 are all about what JSON loses — keywords, namespaces,
;; types. This middleware is where that loss happens on the way in and out, so
;; it is written here rather than hidden inside a content-negotiation library.
;; ---------------------------------------------------------------------------

(defn- read-body [request]
  (when-let [body (:body request)]
    (let [text (slurp body)]
      (when (seq text) (json/read-str text :key-fn keyword)))))

(defn- respond [status body]
  {:status  status
   :headers {"content-type" "application/json"}
   :body    (json/write-str body)})

;; ---------------------------------------------------------------------------
;; Status codes are lab 2's two columns, at the transport layer.
;;
;;   400  malformed  the schema refused it; the domain never saw it   (lab 22)
;;   422  refused    well-formed, and the domain said no              (lab 8)
;;   409  conflict   the stream moved under you; re-read and retry    (lab 7)
;;
;; 400 and 422 are the distinction lab 2 drew, and most APIs collapse them into
;; one number — which tells a client nothing about whether retrying could ever
;; help. 400 will never succeed unchanged. 422 might, tomorrow.
;; ---------------------------------------------------------------------------

(def ^:private status-for
  {:malformed 400
   :refused   422
   :conflict  409})

(defn- outcome->response
  [{:keys [accepted rejected because]}]
  (if accepted
    ;; A command returns the FACTS, not the resource. Returning the mutated
    ;; entity is a REST habit that pulls you back to resource thinking; what
    ;; happened is the command's business, and current state is a query's.
    (respond 200 {:recorded (mapv (fn [e] {:type    (name (:event/type e))
                                           :version (:stream/version e)
                                           :data    (:data e)})
                                  accepted)})
    (respond (status-for rejected 400)
             {:error rejected :detail (if (map? because) because (str because))})))

;; ---------------------------------------------------------------------------
;; Routes.
;;
;; No `/api/commands/…` prefix: the path names the act, Stripe-style. A `POST`
;; to an act-noun carries intent — `/v1/refunds` *creates a refund*, which is a
;; thing that happened. `PUT /v1/charges/{id}` carries none: at the HTTP level
;; it looks identical to every other PUT, which is ADR-0016's whole point.
;;
;; Name the act, not the entity.
;; ---------------------------------------------------------------------------

(def ^:private truck-id #uuid "0f1c2b3a-0000-4000-8000-000000000001")

(defn- command-route
  "Every command endpoint is the same three lines, which is the point."
  [deps command-type]
  (fn [request]
    (outcome->response
     (intake/submit deps truck-id {:type command-type :data (read-body request)}))))

(defn routes
  "The API surface. A test asserts this table and the command registry are the
  same list — an endpoint with no command, or a command with no endpoint, both
  fail the build."
  [deps]
  [["/health" {:get {:handler (fn [_] (respond 200 {:status "ok"}))}}]
   ["/v1"
    ;; Commands — POST, because they change something and are not idempotent.
    ["/sales"    {:post {:command :buy-flavour
                         :handler (command-route deps :buy-flavour)}}]
    ["/restocks" {:post {:command :load-truck
                         :handler (command-route deps :load-truck)}}]
    ;; Queries — GET, because they are safe and idempotent, and saying so in
    ;; the method is information rather than ceremony. The archive's ROADMAP
    ;; forbids this; the README argues both sides.
    ["/stock"    {:get {:handler (fn [_] (respond 200 {:stock (app/stock deps truck-id)}))}}]]])

(defn handler
  "A function from a request map to a response map. That is all a web layer is."
  [deps]
  (ring/ring-handler
   (ring/router (routes deps))
   (ring/create-default-handler
    {:not-found (fn [_] (respond 404 {:error :no-such-endpoint}))})))
