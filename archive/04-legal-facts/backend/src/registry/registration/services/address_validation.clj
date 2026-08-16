(ns registry.registration.services.address-validation
  (:require [clojure.string :as str]))

;; =============================================================================
;; Address validation — BR-010, CRA s4, s5
;;
;; In production this would call An Post AddressComplete or equivalent.
;; This implementation checks that required fields are present and
;; the address is plausibly structured.
;;
;; Replace make-validator with an adapter to a real address service.
;; =============================================================================

(defn- address-plausible? [address]
  (and (some? address)
       (not (str/blank? (:address-line-1 address)))
       (not (str/blank? (:city address)))
       (not (str/blank? (:country address)))))

(defn make-validator
  "Returns an address validation function.
   In production, inject a real address service client here."
  []
  (fn [address]
    (address-plausible? address)))
