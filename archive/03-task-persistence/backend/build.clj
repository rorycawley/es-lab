(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn uber [_]
  (b/delete {:path "target"})
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/compile-clj {:basis     @basis
                  :ns-compile '[es-lab.task-persistence.core]
                  :class-dir  class-dir})
  (b/uber {:class-dir class-dir
           :uber-file "target/app.jar"
           :basis     @basis
           :main      'es-lab.task-persistence.core}))
