# Lab 1: an event

This lab explores what an event is. 

An event, or more precisely a Domain Event, We say an event is something that happenned and holds some significant meaning or value to the business domain. 

The event we'll model is to do with a (single) Ice Cream truck, and it's called 'flavour sold'. It's supposed to be the flavour of the ice-cream sold. Let's say we sold a vanilla ice-cream we'd have:

{:event/type   :flavour-sold
 :flavour       :vanilla}

Events often divide up between event envelope and data. `:data` is the information that constitutes the event.

{ :event/type   :flavour-sold
  :data {
     :flavour       :vanilla
  }
}

We use `:data` rather than `:payload` here. A payload is a blob being carried somewhere in transit; what we're modelling is not that. It's the data of a historical fact — something that has already happened and is being kept as a durable record, not shipped anywhere. `:payload` is the right word for a transport or message envelope; `:data` is the right word for a persisted domain event.