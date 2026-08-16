(ns cart.port.cart-command
  "Command-side application port. Driving adapters submit commands here instead
   of deriving streams or calling the event-store-backed application service
   directly.")

(defprotocol CartCommand
  (handle-cart-command [this cart-id command]
    [this cart-id command expected-version]
    "=> [:ok {...}] | [:error {:reason kw}] | [:conflict {:expected n :current n}]"))
