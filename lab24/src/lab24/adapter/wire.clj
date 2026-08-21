(ns lab24.adapter.wire
  "JSON on and off the wire, and nothing else.

  Labs 19, 20 and 23 are all about what serialisation loses — keywords,
  namespaces, types. This is where that loss happens, kept in plain sight
  rather than hidden inside a content-negotiation library.

  Three namespaces need it: the HTTP adapter, the authentication middleware
  that sits in front of it, and the mock identity provider — which is a
  *different system* that happens to run in this process. Sharing a JSON
  encoder across a system boundary is plumbing, not coupling: there is no
  domain in this file, and there must never be."
  (:require [clojure.data.json :as json]))

(defn read-body
  "The request body as data, or nil. A JSON body has no keywords, so what
  comes out of here is wire-shaped and must be decoded before it is trusted."
  [request]
  (when-let [body (:body request)]
    (let [text (slurp body)]
      (when (seq text) (json/read-str text :key-fn keyword)))))

(defn respond
  ([status body] (respond status body {}))
  ([status body headers]
   {:status  status
    :headers (merge {"content-type" "application/json"} headers)
    :body    (json/write-str body)}))
