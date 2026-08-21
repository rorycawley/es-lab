(ns lab24.serve
  "Start the system with an HTTP server on it, and wait.

  Two servers come up, on two ports, and that is the point rather than an
  inconvenience: one is the application and one is the identity provider, and
  they are different systems. Yours holds no user records and issues no
  tokens; it fetches a public key and checks a signature."
  (:require [lab24.mock-idp :as mock-idp]
            [lab24.system :as system])
  (:gen-class))

(def port 3000)

(defn -main [& _]
  (let [idp (mock-idp/start! {:access-token-seconds 300})
        sys (system/start (system/serving
                           (system/in-memory {:oidc (mock-idp/oidc-config idp)})
                           port))]
    (println)
    (println "  The truck      http://localhost:" port)
    (println "  The provider  " (:issuer idp))
    (println)
    (println "  Sign in as Dana (a driver), Sam (a driver on another truck)")
    (println "  or Rudi (the depot), and keep the access token:")
    (println)
    (println "    TOKEN=$(curl -s -X POST" (:token-endpoint idp) "\\")
    (println "      -d grant_type=password -d username=dana -d password=cone \\")
    (println "      -d client_id=truck-till-dana -d client_secret=s3cret \\")
    (println "      | jq -r .access_token)")
    (println)
    (println "  Then:")
    (println)
    (println "    curl -s localhost:3000/v1/stock -H \"authorization: Bearer $TOKEN\"")
    (println "    curl -s localhost:3000/v1/sales -H \"authorization: Bearer $TOKEN\" \\")
    (println "      -d '{\"flavour\":\"vanilla\"}'")
    (println)
    (println "  And without it, to watch the door hold:")
    (println)
    (println "    curl -s -o /dev/null -w '%{http_code}\\n' localhost:3000/v1/stock")
    (println)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(do (system/stop sys) (mock-idp/stop! idp))))
    @(promise)))
