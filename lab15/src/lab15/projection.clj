(ns lab15.projection
  "A read model — and the place erasure actually leaks.

  Shredding a key makes the log unreadable. It does nothing whatever for a
  projection that already materialised the plaintext, because that copy was
  made while the key still existed and lives in a store nobody encrypted.

  Which turns lab 9's 'read models are disposable' from a nice property into
  an obligation: erasure means destroy the key **and rebuild every projection
  that touched the data**."
  (:require [lab15.reading :as reading]))

(def initial-model {:customers {} :sales {}})

(defmulti apply-event (fn [_model event] (:event/type event)))

(defmethod apply-event :card-issued
  [model event]
  (let [{:keys [customer-id personal]} (:data event)]
    (assoc-in model [:customers customer-id] personal)))

(defmethod apply-event :flavour-sold
  [model event]
  (let [{:keys [customer-id flavour]} (:data event)]
    (update-in model [:sales customer-id] (fnil conj []) flavour)))

(defmethod apply-event :default
  [model _event]
  model)

(defn rebuild
  "Fold the log from the beginning, reading through the vault as it stands now.

  Rebuilding after a key is destroyed is what makes the erasure reach the read
  side. Before the rebuild, the old model still holds the name."
  [vault log]
  (reduce apply-event initial-model (reading/read-all vault log)))

(defn name-of
  [model customer-id]
  (let [personal (get-in model [:customers customer-id])]
    (if (reading/erased? personal) reading/erased (:name personal))))
