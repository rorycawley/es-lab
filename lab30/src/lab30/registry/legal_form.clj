(ns lab30.registry.legal-form
  "Legal form is structured registry data, not part of a company's filed
  name. Rendering is a UI-language decision and query suffixes are tolerated
  at the boundary without polluting every search key."
  (:require [clojure.string :as str])
  (:import (java.util Locale)))

(def labels
  {:plc    {:fr "SA" :de "AG" :it "SpA"}
   :llc    {:fr "Sàrl" :de "GmbH" :it "Srl"}
   :lp     {:fr "SCS" :de "KG" :it "SAS"}
   :gp     {:fr "SNC" :de "OHG" :it "SNC"}
   :coop   {:fr "Coop." :de "Gen." :it "Soc. coop."}
   :se     {:fr "SE" :de "SE" :it "SE"}
   :branch {:fr "succursale" :de "Zweigniederlassung" :it "succursale"}
   :other  {:fr "" :de "" :it ""}})

(def ^:private query-suffixes
  (->> labels vals (mapcat vals) (remove str/blank?) distinct
       (sort-by (comp - count))))

(defn strip-query-suffix
  [query]
  (let [query (str/trim (str query))
        lower #(.toLowerCase ^String % Locale/ROOT)
        folded (lower query)]
    (or
     (some (fn [suffix]
             (let [needle (str " " (lower suffix))]
               (cond
                 (= folded (lower suffix)) ""
                 (str/ends-with? folded needle)
                 (str/trim (subs query 0 (- (count query) (count needle))))
                 :else nil)))
           query-suffixes)
     query)))

(defn display-name
  [filed-name legal-form ui-language]
  (let [suffix (get-in labels [legal-form ui-language] "")]
    (cond-> filed-name
      (not (str/blank? suffix)) (str " " suffix))))
