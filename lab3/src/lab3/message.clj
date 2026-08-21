(ns lab3.message
  "Static examples of an Integration Message: telling another module/system
  that 'flavour sold' happened, for an Ice Cream truck.")

(def flavour-sold-vanilla-integration-message
  {:message/type :flavour-sold
   :payload      {:flavour "vanilla"}})

(def flavour-sold-chocolate-integration-message
  {:message/type :flavour-sold
   :payload      {:flavour "chocolate"}})

(def examples
  [flavour-sold-vanilla-integration-message
   flavour-sold-chocolate-integration-message])

;; The transport envelope and the published contract are separate layers.
;; `:message/id` identifies this send. `:fact-id` is the stable identity of the
;; domain event being announced, represented as a wire-safe string under an
;; unnamespaced contract key (lab 20). Correlation and causation describe the
;; chain and may also appear on internal command/event envelopes (lab 11).
(def flavour-sold-vanilla-message
  {:message/id   #uuid "7f2678a4-2bd3-4f8e-9a87-7ce7607b1d37"
   :message/type :flavour-sold
   :payload      {:fact-id "018f7a3e-0000-7000-8000-000000000001"
                  :flavour "vanilla"}
   :metadata     {:correlation-id #uuid "cc79c083-c1d0-45a5-b18f-5079a3720901"
                  :causation-id   #uuid "31dd15c7-63e4-48ef-a751-12d971e95acc"}})
