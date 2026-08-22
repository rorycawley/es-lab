# Recording more events… But where will we store them?

**Published:** 2025-10-16

Java

Software Development

Software Engineering

Kotlin

Distributed Systems


![Image](https://miro.medium.com/v2/resize:fit:6488/1*AWZDtN_QJN7sCBJbwZmX0A.png)

As we’re scaling up, streaming and recording all our product events is becoming unwieldy and challenging. At Revolut, we have our own way of handling event streaming. We’re developing a system to store and provide streaming access to all our business-related events (spoiler alert: we’re not using Kafka).

But, for a moment, let’s go to the circus. Imagine you’re juggling several balls. You need to be fast and perfectly accurate. Sometimes the ringmaster adds more balls for you, so you always have to be ready to adapt to a higher load. And one more thing — all the balls are crystal!

Now back to reality. Every money-related event is significant; it’s a crystal ball we must catch and handle with care. Such events include money transfers, changing user data, any card actions, and more.

Everything associated with processing financial operations requires 100% consistency — money is a very sensitive matter. At the same time, we have to keep up with the pace of thousands of new users daily. Exponential scale-up also influences feature delivery — we keep developing new, and existing, products, but new dimensions of complexity are coming in. All this puts us in a special position that requires a dedicated solution.

## Why not use an off-the-shelf solution?

We’ve considered this option. While making our decisions, we took into account that Revolut is growing rapidly. We need to provide a system that can scale horizontally and adapt to changing amounts of data and traffic. Additionally, we may face corner cases which require quick action and a solution tailored to us — we want to keep the possibility of applying such changes quickly. A ready solution like Kafka might have solved some of our problems, but it would also introduce the complexity of maintenance, configuration, and keeping it available. Therefore, we’ve chosen to invest in a custom solution that will grow with the problems we want to solve.

Having our own solution allows us to **control the whole life of an event** — from the moment it’s created as part of a business transaction, to the moment it gets to the EventStore and is streamed via the EventStream. It also unties our hands with regards to tuning; we’ve implemented all the functionality we couldn’t get in third-party solutions. We’ve added the following features that are not supported by Kafka (at least not in a way that would serve our needs effortlessly):

*   Ad-hoc queries and easy data inspection with SQL thanks to storing events in PostgreSQL.
*   Querying by time of event — as we partition events by timestamp.
*   Guaranteed consistency between state changes and persisted events, for example, card blocking in the state database guarantees the CardBlocked event.
*   Relationships: we have a nested structure of events (an action event with zero, one or many model events), and we need to stream model events, but not action events. We could look into denormalising/flattening this structure to adapt to a third-party solution, but we would have to face unnecessary data redundancy as a side effect.
*   The system should allow consumers to query events based on an arbitrary payload filter instead of processing all events or a whole partition.
*   Easy archiving of old events.
*   Parallel event stream processing on multiple consumer nodes split by an arbitrary partition key.

And there’s more to come ─ next, we want to have a guaranteed natural order of events per model.

We don’t want any surprises that may cause incidents. Kafka requires expertise in handling corner cases. For instance, its default configuration is set up with throughput in mind, and not consistency. Things like fsync aren’t performed by default, so some data loss or inconsistency between producer/consumer is possible.

Whichever solution we choose, we would still need a database, like Postgres, and an application layer. Although a ready solution cannot replace our whole system, it may take over some of its functions. For example, Kafka can be used to take over the responsibility of a short-term (e.g. 7-day) persisted event stream. From a throughput perspective, there are alternatives like Pulsar (event streaming with better performance than Kafka) and TimescaleDB (time-series database extension for Postgres for optimised time-based storage and queries).

## Areas of application

### **Customer services**

When you deal with money and something goes wrong, you need to know every action related to the operation. All money transactions, roll-backs, and the corresponding user data are recognised and logged. Customer services can use this audit log to react to the problem adequately.

### **Risk detection**

Some services plug into EventStream to consume data as needed. They run models (often based on machine learning algorithms) that assess exposure to different forms of financial crime risk.

### **Real-time monitoring in FX operations**

One of the most popular Revolut features is currency exchange. We allow our customers to do foreign exchange cheaper than other banks. So to facilitate our main feature, we need to monitor all transactions coming to our system in real-time. We perform automated hedging of our FX risk exposure in real-time.

### **Marketing and promotion**

We consume events, observe patterns and behaviours, and based on the results we trigger personalised marketing campaigns and promotions.

## Our expectations of the solution

In the beginning, all we needed was a system to exchange events and to be able to query them dynamically. Later, as the Revolut product was developing, our requirements evolved accordingly, and the following things came forward:

*   Accuracy of business data
*   High availability of services
*   Ability to make advanced queries — like filtering Transaction events by their type
*   Scalability of the system
*   Immutability of data
*   Low latency, or having the ability to control latency (full processing of event including storage and streaming in around 50–200 ms)
*   Observability — why and where did an incident happen, could it have been predicted

We looked at the event streaming solutions that were available in the market at the time, back in early 2016, but couldn’t find one that could cover all our needs at once. For example, the ability to make ad-hoc queries would be missing, or maintenance of the third-party solution required substantial expertise to deal with corner cases.

## Get Jacek Spólnik’s stories in your inbox

Join Medium for free to get updates from this writer.

Subscribe

Subscribe

Remember me for faster sign in

Therefore, we created our own event streamer at Revolut. This is it.

## Our solution design

![Image](https://miro.medium.com/v2/resize:fit:2000/1*ff-LXVkcKHLTA3I0jJZ_zw.jpeg)

Most Revolut services use PostgreSQL for storing data. For events, we also use PostgreSQL along with the listen/notify mechanism, so we can reuse all our learnings from different teams, and so consumers can make advanced queries to the database. As we use [PostgreSQL replication](https://www.postgresql.org/docs/9.1/high-availability.html), we have two kinds of database instances — master and replica. We use a master for storing events, and a replica for streaming and fetching.

We have the EventStore application which is responsible for the orchestration of storing and fetching of events. This EventStore implementation is stateless, as all events land in Postgres. It is written in Kotlin using the JetBrains [Ktor framework](https://ktor.io/) and coroutines. We use two instances of the EventStore application, but you can have more depending on the load — their number is scaled horizontally. The load between the instances is handled by a load balancer.

We also have the EventStream cluster that is purely responsible for streaming events to subscribers. The EventStream is developed in Java 11 using RSocket to handle the resilience of communication between consumers and the EventStream.

We use the Google Cloud Platform, specifically virtual private clouds to isolate different parts of the system and facilitate proper access management. We separate the storage from the application, which is actually decoupled from the API gateways.

And finally, we’ve got sets of microservices that perform the roles of a publisher, a consumer, or just a service that fetches events. We decided to develop SDKs for our services — keeping the client SDK and the server under one team’s control allows us to minimise backward compatibility issues, abstract protocol, and make changes to REST API easier.

## Achieving accuracy and consistency

There are two options here; event sourcing and event streaming. You can choose the [event sourcing](https://martinfowler.com/eaaDev/EventSourcing.html) approach when you first generate an event and then process it. But we want the end-user experience to be the same as if we did synchronous processing with immediate feedback. That’s why we’ve chosen the second option. We change the model and then based on this change we generate an event. So now we have two separate things — the model and the event — that we need to keep consistent. If you roll back one thing, you have to roll back the other one, too. If one of them succeeds — you know the other one succeeded too.

![Image](https://miro.medium.com/v2/resize:fit:2000/1*o8ocZ0IXcy3qVUhunIbAEA.jpeg)

Every part of the flow, every microservice, and every EventStore has its own database. When a service produces an event, instead of sending an event, we change the model in the database and to achieve consistency we save both — the model and the event in the same database. The transaction will succeed or roll back — and the system will either roll back both the model and the event or will save both. So once we succeed with our model change, the event is saved to the database. And then we asynchronously publish the events. We send them with retries and use the mechanism that guarantees delivery, giving us consistency.

![Image](https://miro.medium.com/v2/resize:fit:2000/1*OJk586oKDGEzFmECIwqZUg.jpeg)

Is it as simple as that? Not really. We actually have few ways to send events to make sure events are delivered. During a business action, we post an event to EventStore, potentially with retries in case of failure — while at the same time storing the event in EventLog. If the event isn’t published successfully, our background reconciliation process will recognise that in EventLog and will resend them until they succeed. We keep all the events for 24 hours (configurable), thus requiring us to address any infrastructure or other issues within this timeframe.

We also do the cleanup to manage the size of the EventLog table. As mentioned, all events older than 24 hours would be cleaned, which allows us to keep this EventLog table fairly small.

When the successful event is saved to the EventStore database, we use the [LISTEN/NOTIFY Postgres mechanism](https://www.postgresql.org/docs/11/sql-notify.html) — at any point in time we can notify EventStream via Postgres that some event happened. So whenever an event is written to the database, PostgreSQL triggers events that contain the full event message to all EventStream instances. Later, EventStream publishes these events to all consumers that subscribed to them with matching criteria.

### **Dealing with reconnections**

Suppose a consumer starts getting events, but the processing is slow — we use a backpressure mechanism in this case. At some point, the queue may be full, so we pause processing and, once the consumer finishes processing the queue, it can start asking for more events. Additionally, for any downtime of the service, the consumer may get back at any point based on the timestamp which is set on the insert by PostgreSQL.

We have two parts of the streaming process. Whenever a new subscription is made, it starts in the offline mode as it’s based on the last snapshot time or last processed event time — this works against any of the replicas. Once the offline stream catches up, it switches to the online mode, which is utilizing the LISTEN/NOTIFY Postgres mechanism — using logical replicas in this case.

## Domain splitting

Another important thing we have to deal with in our distributed system is domain splitting — we solve it using a Saga pattern. As all business transactions are represented as \`Actions\` — we can define a saga chain of all related actions — making sure we can observe the state of processing them and retry at any failure — or recover using events consumers. The fallbacks using event consumers works because every Action produces events — so we can be sure that at any given point we can recover from events. This requires that every Action we write is idempotent as it will be invoked a few times — from processing Saga flow or from event consumers (fallbacks).

## Events reconciliation mechanism

Whenever an event is published to the EventStore, we have a fallback mechanism.

![Image](https://miro.medium.com/v2/resize:fit:2000/1*RCKvf98pBUanD5C8jxfi6A.jpeg)

Suppose we have three instances of the same application that processes business transactions. During business operations, they additionally send events about them. If any of them fails, the unprocessed events would be present in EventLog. Now, we don’t want all the applications to reconcile failed events, because that would cause a lot of unnecessary work and failures to update successful events (as other applications would do the same work). To solve the problem of resending failed events — we need one of the apps to become what we call an event reconciler. This instance of the publisher will do the reconciliation processing — it basically means reading events older than 30 s from the EventLog that are not marked as published and re-sending them. Thus, we can guarantee that sooner or later each event will be published.

## Conclusion

As you may imagine, the solution we have is quite complex and still evolving. There are still lots of challenges we need to go through, but our solution gives us the flexibility and performance we need so the ever-growing demand can be met. This is the first in a series about our Event Streaming platform — check back to see more!

## Want to work with me?

Revolut is a global community with offices in London, Moscow, St. Petersburg, Krakow, New York, Berlin, Vilnius and other cities. The number of our customers has already exceeded 10 million. We’re looking for engineering talent to help us build the best money management solution. Check out our vacancies on the Careers page and join us.

[

## Careers | Revolut

### Banks are powerful. They owe us nothing. They watch us. They punish us for our mistakes. They have more money, more…

www.revolut.com


](https://www.revolut.com/careers/department/technology?source=post_page-----4b1dad457cf5---------------------------------------)