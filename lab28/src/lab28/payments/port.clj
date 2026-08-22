(ns lab28.payments.port
  "The driven port Payments talks to the outside world through.

  Everything in this namespace is in the language of taking money. There is no
  payment intent, no `pm_card_visa`, no `pi_`. That is not decoration: it is
  the difference between choosing a provider and being married to one, and
  `architecture_test.clj` fails the build if a vendor word crosses this line.

  ## The contract

  `authorize!` takes a charge and returns an outcome. It **must be idempotent
  on `:payment-id`**: calling it twice with the same payment id must move money
  once and return the same outcome both times. That requirement is not a
  courtesy -- the caller has already written a row saying it intends to charge,
  and a process that dies between the write and the call has no way to know
  whether the money moved.

  An adapter that cannot honour it must say so rather than pretend, because a
  port that promises more than its adapters deliver is worse than no port.")

(defprotocol PaymentGateway
  (authorize!
    [this charge]
    "Take `:amount-cents` for `:payment-id`, and return an outcome map.

     charge  {:payment-id :uuid, :amount-cents int, :currency string,
              :instrument string, :description string}

     returns {:outcome :authorized, :reference \"opaque-provider-id\"}
             {:outcome :declined,   :because \"a short reason code\"}
             {:outcome :pending,    :reference \"...\"}

     Throws for anything that is not an answer -- an unreachable provider, a
     shape this adapter does not understand, a credential it will not accept.
     A refusal to take money is an answer. A failure to ask is not.")

  (provider-name
    [this]
    "Which provider this is, for telemetry and for operator-facing messages.
     Nothing may branch on it."))
