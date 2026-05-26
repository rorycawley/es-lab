(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn ^:export uber [_]
  (b/delete {:path "target"})
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/compile-clj {:basis     @basis
                  :ns-compile '[registry.system]
                  :class-dir  class-dir})
  (b/uber {:class-dir class-dir
           :uber-file "target/app.jar"
           :basis     @basis
           :main      'registry.system}))
