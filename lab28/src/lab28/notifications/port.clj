(ns lab28.notifications.port
  "The driven port Notifications talks to the outside world through.

  ## The contract, and why it is weaker than Payments'

  `send!` takes a message and returns an outcome. It offers **at-least-once**
  delivery and nothing better, because email providers do not offer anything
  better. SendGrid has no idempotency key. Nor does SES, Mailgun or Postmark.
  Ask twice and the recipient gets two emails.

  That asymmetry with `payments.port` is the point of having both in one lab.
  A port cannot invent a guarantee the other side does not provide. It can
  only state honestly which guarantee it has, so that callers stop assuming.

  `:notification-id` is therefore not an idempotency key. It travels with the
  message so that a duplicate is *recognisable afterwards* -- in the provider's
  logs, in a bounce webhook, in a support conversation -- which is a strictly
  weaker and much more common kind of help.")

(defprotocol Emailer
  (send!
    [this message]
    "Send one message, and return an outcome map.

     message {:notification-id :uuid, :to string, :subject string,
              :body string}

     returns {:outcome :sent,     :reference \"opaque-provider-id\"}
             {:outcome :rejected, :because \"a short reason code\"}

     Throws for anything that is not an answer. A provider refusing this
     recipient is an answer. A provider being unreachable is not.")

  (provider-name [this] "Which provider this is. Nothing may branch on it."))
