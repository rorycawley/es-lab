(ns cart.application.command-pipeline
  "Shared command coordinator implementing the SWR-022 outcome order.

   This namespace is part of the imperative shell. Step functions may call
   ports; the domain rules they invoke remain pure. A step either returns
   `proceed` with the context for the next step or one final public outcome."
  (:refer-clojure :exclude [continue]))

(def final-outcomes #{:success :invalid :rejected :conflict})

(defn proceed
  "Passes context to the next evaluation step."
  [context]
  {:pipeline/status :proceed
   :pipeline/context context})

(defn outcome
  "Terminates evaluation with one of the four SWR-008 outcomes."
  ([category]
   (outcome category nil))
  ([category data]
   (when-not (contains? final-outcomes category)
     (throw (ex-info "Unknown command outcome" {:outcome category})))
   (cond-> {:outcome category}
     (some? data) (assoc :data data))))

(defn- proceed? [result]
  (= :proceed (:pipeline/status result)))

(defn- require-step-result [step-name result]
  (when-not (or (proceed? result)
                (contains? final-outcomes (:outcome result)))
    (throw (ex-info "Command pipeline step returned an invalid result"
                    {:step step-name :result result})))
  result)

(defn- run-step [step-name step context]
  (require-step-result step-name (step context)))

(defn evaluate
  "Evaluates one command delivery in the fixed SWR-022 order.

   `validate-input` must return normalized context containing
   `:command/observation-required?`. First additions set it to false; every
   existing-cart command sets it to true. Replay success or request-ID misuse
   terminates before currency. Currency terminates before business rules."
  [{:keys [validate-input resolve-replay check-observation apply-business-rules]}
   request]
  (doseq [[step-name step] [[:validate-input validate-input]
                            [:resolve-replay resolve-replay]
                            [:check-observation check-observation]
                            [:apply-business-rules apply-business-rules]]]
    (when-not (ifn? step)
      (throw (ex-info "Command pipeline requires every step"
                      {:missing-or-invalid-step step-name}))))
  (let [validated (run-step :validate-input validate-input request)]
    (if-not (proceed? validated)
      validated
      (let [context  (:pipeline/context validated)
            replayed (run-step :resolve-replay resolve-replay context)]
        (if-not (proceed? replayed)
          replayed
          (let [context (:pipeline/context replayed)
                checked (if (:command/observation-required? context)
                          (run-step :check-observation check-observation context)
                          (proceed context))]
            (if-not (proceed? checked)
              checked
              (run-step :apply-business-rules
                        apply-business-rules
                        (:pipeline/context checked)))))))))
