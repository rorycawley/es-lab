(ns lab24.demo
  "A morning on the truck, with everybody having to say who they are.

  This runs against the HTTP adapter rather than the application layer, which
  is a change from lab 23 and a deliberate one: what this lab is about only
  exists at the wire. A token arrives in a header or it does not.

  It lives in `dev/` for the same reason `mock_idp.clj` does — it needs an
  identity provider, and an identity provider is not part of the application."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [lab24.adapter.http :as http]
            [lab24.app :as app]
            [lab24.mock-idp :as mock-idp]
            [lab24.port.driven :as driven]
            [lab24.system :as system])
  (:gen-class))

(def truck-1 #uuid "0f1c2b3a-0000-4000-8000-000000000001")

(defn- say [& parts] (println (apply str parts)))
(defn- rule [] (say "  " (apply str (repeat 68 "─"))))

(defn- call
  [handler method uri tokens body]
  (let [response (handler (cond-> {:request-method method :uri uri}
                            tokens (assoc :headers {"authorization" (mock-idp/bearer tokens)})
                            body   (assoc :body (java.io.StringReader. (json/write-str body)))))]
    (assoc response :parsed (json/read-str (:body response) :key-fn keyword))))

(defn- show
  [label {:keys [status parsed]}]
  (say (format "     %-3s  %-34s %s"
               status
               label
               (if-let [recorded (:recorded parsed)]
                 (str/join ", " (map :type recorded))
                 (or (when (map? (:detail parsed)) (pr-str (:detail parsed)))
                     (:detail parsed)
                     (:error parsed)
                     (pr-str parsed))))))

(defn run
  [handler idp]
  (say)
  (say "  An Ice Cream truck, one morning, with the doors locked")
  (rule)

  (say "  1. Nobody is anybody yet.")
  (show "no token" (call handler :post "/v1/sales" nil {:flavour "vanilla"}))

  (say)
  (say "  2. Rudi signs in at the depot and gets on with it.")
  (let [rudi (mock-idp/login idp :rudi)]
    (say "     access token  " (subs (:access_token rudi) 0 32) "…  (offline-verifiable, short)")
    (say "     refresh token " (subs (:refresh_token rudi) 0 32) "…  (server-revocable, long)")
    (show "rosters Dana" (call handler :post "/v1/driver-assignments" rudi {:driver-id "USR-83721"}))
    (show "loads two vanilla" (call handler :post "/v1/restocks" rudi {:flavour "vanilla" :quantity 2}))

    (say)
    (say "  3. Three ways to be told no, and they are three different things.")
    (show "Rudi sells — wrong role (RBAC)"
          (call handler :post "/v1/sales" rudi {:flavour "vanilla"}))
    (let [sam (mock-idp/login idp :sam)]
      (show "Sam sells — wrong truck (ABAC)"
            (call handler :post "/v1/sales" sam {:flavour "vanilla"}))
      (show "Sam claims to be Dana in the body"
            (call handler :post "/v1/sales" sam {:flavour "vanilla"
                                                 :actor {:type "user" :id "USR-83721"}})))

    (say)
    (say "  4. Dana signs in, and may.")
    (let [dana (mock-idp/login idp :dana)]
      (show "sells one" (call handler :post "/v1/sales" dana {:flavour "vanilla"}))

      (say)
      (say "  5. The same query, answered differently by who is asking (ADR-0020).")
      (show "Dana reads stock" (call handler :get "/v1/stock" dana nil))
      (show "Rudi reads stock" (call handler :get "/v1/stock" rudi nil))

      (say)
      (say "  6. Dana's access token is good for two seconds. Waiting…")
      (Thread/sleep 2500)
      (show "the same token, later" (call handler :post "/v1/sales" dana {:flavour "vanilla"}))
      (let [refreshed (mock-idp/refresh idp :dana (:refresh_token dana))]
        (say "     the refresh token buys a new one, with no second login")
        (show "and the last cone goes" (call handler :post "/v1/sales" refreshed {:flavour "vanilla"})))))

  handler)

(defn- show-stream [app]
  (doseq [event (driven/read-stream (:store app) truck-1)]
    (let [{:keys [type id]} (get-in event [:metadata :actor])]
      (say (format "     v%-2s  %-16s %s %s"
                   (:stream/version event)
                   (name (:event/type event))
                   (name type)
                   id)))))

(defn -main [& _]
  (let [idp (mock-idp/start! {:access-token-seconds 2})
        sys (system/start (system/in-memory {:oidc (mock-idp/oidc-config idp)}))
        app (system/app sys)]
    (try
      (run (http/handler app) idp)

      (say)
      (say "  7. A policy notices the depletion and restocks (lab 10) — and the")
      (say "     restock is NOT authorised by whoever emptied the truck.")
      (let [{:keys [commands]} (app/react app 0)]
        (doseq [command commands]
          (say "     command  " (name (:command/type command))
               "  actor " (pr-str (:command/actor command)))))

      (say)
      (say "  8. Every fact remembers who is answerable for it — and no token.")
      (show-stream app)

      (say)
      (rule)
      (say "  Correlation travels the whole chain. Authority stops at the person.")
      (say)
      (finally (system/stop sys) (mock-idp/stop! idp)))))
