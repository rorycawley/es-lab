(ns lab29.platform.contract
  "A module's public contract, as data rather than as a document.

  The messaging document suggests writing each module's contract down --
  commands consumed, events consumed, queries provided, events published --
  as \"one of the clearest ways to understand the architecture without opening
  implementation code\". That is true, and a document drifts.

  So the contract is a value each module declares, the routing registry is
  *derived* from those values rather than written by hand, and a contract that
  does not add up refuses to start. There is no second copy to fall out of
  step with the first, which is lab 23's trick -- keeping the endpoint list and
  the command vocabulary the same list -- applied to messaging."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def Contract
  [:map {:closed true}
   [:module :simple-keyword]
   [:handles-commands [:set :qualified-keyword]]
   [:consumes-events [:set :qualified-keyword]]
   [:publishes-events [:set :qualified-keyword]]
   [:provides-queries [:set :qualified-keyword]]])

(defn- duplicates [pairs]
  (->> pairs (group-by first) (keep (fn [[k vs]] (when (< 1 (count vs)) [k (mapv second vs)])))))

(defn- problems
  [contracts]
  (let [handled     (for [c contracts t (:handles-commands c)] [t (:module c)])
        published   (for [c contracts t (:publishes-events c)] [t (:module c)])
        consumed    (for [c contracts t (:consumes-events c)] [t (:module c)])
        command-set (set (map first handled))
        event-set   (set (map first published))]
    (concat
     ;; Two modules answering one request is not fan-out, it is an argument
     ;; about who owns the capability.
     (for [[t owners] (duplicates handled)]
       (str "command " t " is handled by " (str/join " and " owners)))
     ;; Two modules publishing one fact means the fact has two meanings.
     (for [[t owners] (duplicates published)]
       (str "event " t " is published by " (str/join " and " owners)))
     ;; Subscribing to something nobody sends is a silent no-op, and silent is
     ;; the worst way for a subscription to be wrong.
     (for [[t module] consumed
           :when (not (event-set t))]
       (str "event " t " is consumed by " module " and published by nobody"))
     ;; A type that is both is a type whose cardinality nobody can state.
     (for [t (set/intersection command-set event-set)]
       (str t " is used as both a command and an event")))))

(defn routes
  "Fold declared contracts into a routing registry, or refuse to start.

  Returns `{:commands {type module} :events {type #{modules}}}`."
  [contracts]
  (let [found (problems contracts)]
    (when (seq found)
      (throw (ex-info (str "The module contracts do not add up: "
                           (str/join "; " found))
                      {:reason :incoherent-contracts :problems (vec found)})))
    {:commands (into {} (for [c contracts t (:handles-commands c)] [t (:module c)]))
     :events   (reduce (fn [acc [t module]] (update acc t (fnil conj #{}) module))
                       {}
                       (for [c contracts t (:consumes-events c)] [t (:module c)]))
     :queries  (into {} (for [c contracts t (:provides-queries c)] [t (:module c)]))}))
