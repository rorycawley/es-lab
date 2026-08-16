(ns registry.registration.ports.register-port)

(defprotocol RegisterPort
  (company-by-registration-number [port registration-number])
  (search-by-name                 [port name-fragment]))
