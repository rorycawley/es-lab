# PostgreSQL for Everything - Dr. Raphael A. Bauer, MBA | Fractional & Interim CTO | PE/VC Tech Strategy & Due Diligence | Berlin | London | Austin, Texas

Contrary to popular belief - the answer to everything is NOT 42 - it’s PostgreSQL. (ok. It might also be [Postgres](https://wiki.postgresql.org/wiki/FAQ#What_is_PostgreSQL.3F_How_is_it_pronounced.3F_What_is_Postgres.3F)).

_This article was [discussed on Hacker News](https://news.ycombinator.com/item?id=49361279). The thread contains a lot of additional links, real-world experience and critical thoughts - well worth reading._

## Table Of Contents

## Intro

I started using PostgreSQL roughly in 2003 for a research project called [ColumbaDB](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC1087474/). Columba is no more, but PostgreSQL is still alive and kicking more than ever.

In 2003, MySQL was much more widely used than PostgreSQL. MySQL was also potentially faster as it did not implement all features of the SQL standard. At the same time MySQL was lacking many features that we needed (full-text search, powerful indexes, SQL standard compliance etc). PostgreSQL felt more like a “real” database in comparison to MySQL - like a tiny version of Oracle - but in open source clothes.

During that research project I learned a lot about databases, indexes and the power of PostgreSQL. One important use-case was full-text search. We could have used MySQL in conjunction with another system like Lucene / Solr to make our database searchable. But that would have meant running and maintaining two such systems. Complicated.

PostgreSQL allowed us to use a [fulltext search plugin](https://www.postgresql.org/docs/current/textsearch.html) to do everything in one system. No need to sync any data. No need to maintain and run two systems. It just worked and made us smile (after some tweaks of course). Simplicity.

Since then I used PostgreSQL for many use-cases throughout my career as [CTO / Interim Manager](https://www.raphaelbauer.com/interim-cto/). Most recently I used PostgreSQL to store very high volume web analytics time series data via its [TimescaleDB plugin](https://www.timescale.com/). Check out [Privatracker - the best way to do web analytics and respect the privacy of your visitors](https://www.privatracker.com) - to see it in action.

Many others discussed the topic from different angles. And each article is really worth your time ([SQL is Agile](https://lucumr.pocoo.org/2012/12/29/sql-is-agile/), [Stephan Schmidt on Using SQL for Everything](https://www.amazingcto.com/postgres-for-everything/)). Also check out my [Linkedin post](https://www.linkedin.com/posts/raphaelabauer_postgresql-for-everything-activity-7163447276114317313-mSrw?utm_source=share&utm_medium=member_desktop).

And if you are using PostgreSQL I can highly recommend reading Hazel Bachrach’s nice post on [“What I Wish Someone Told Me About Postgres”](https://challahscript.com/what_i_wish_someone_told_me_about_postgres).

In my humble opinion the power of PostgreSQL comes from three sources:

1.  It is rock-solid and stable.
2.  It is easy to run, install and scale.
3.  It massively simplifies your IT setup by being not only a RDBMS, but also a full-text search engine, a document storage and much much more…

Let’s have a closer look…

## Rock Solid and Stable

PostgreSQL is boring old technology. [The first PostgreSQL release dates back to 1996](https://de.wikipedia.org/wiki/PostgreSQL). PostgreSQL is also very widely used - for a very long amount of time. Ironing out bugs - especially in database systems - takes time. PostgreSQL had that time.

It also has a very active community that diligently adds more and more features without breaking any old parts of it. In recent years PostgreSQL got many amazing features like json document storage, partitioning support, common table expressions and much much more. Each new release of PostgreSQL is exciting and brings new nice features.

True - PostgreSQL is old - but the features are very very modern - and PostgreSQL becomes better with every release.

## Easy to Run, Install and Scale

PostgreSQL can be installed very easily locally. It is bundled with all major Linux distributions, part of [Mac brew](https://wiki.postgresql.org/wiki/Homebrew), but can also be installed with applications like [PostgresApp](https://postgresapp.com/).

When running tests, it comes in handy using [Testcontainers with PostgreSQL](https://java.testcontainers.org/modules/databases/postgres/). It was never easier running your tests against a real PostgreSQL database that is 100% similar to the production thing.

If you want to run PostgreSQL on a server then you can simply apt-get install it. Or run it in a [docker container](https://hub.docker.com/_/postgres).

All cloud providers allow you to run (and scale!) PostgreSQL by clicking a single button. You got ample of choice at your fingertips:

-   [Amazon AWS](https://aws.amazon.com/rds/postgresql/)
-   [Google GCP](https://cloud.google.com/sql/docs/postgres)
-   [Microsoft Azure](https://azure.microsoft.com/en-gb/products/postgresql/)
-   [ElephantSQL](https://www.elephantsql.com/)
-   [CrunchyData](https://www.crunchydata.com/)
-   [Timescale](https://www.timescale.com/)
-   … and many more …

That makes PostgreSQL one of the most widely supported software systems in the market. And for you this means less maintenance and more time for creating new features for clients.

## Simplifies Your IT Setup

Running PostgreSQL in the cloud is already just one click. But it gets even better. PostgreSQL can replace many systems that youd’d have to run otherwise.

### PostgreSQL Replaces Solr and Elastic: Full-Text Search

PostgreSQL allows you to turn your text data into user-searchable data. Without a separate system. It’s also language agnostic and you’ll never have any sync problems between your data and your fulltext search system.

The most impressive article on the topic is how [Contentful used PostgreSQL to enable fulltext search for their users](https://www.contentful.com/blog/contentful-faster-full-text-search/). It’s a tale in simplicity that enables growth.

Instacart did something very similar: They [built their modern search infrastructure on Postgres](https://tech.instacart.com/how-instacart-built-a-modern-search-infrastructure-on-postgres-c528fa601d54) instead of running a separate search cluster. Same story, different company.

The built-in [`tsvector` / `tsquery`](https://www.postgresql.org/docs/current/datatype-textsearch.html) machinery is a very good starting point. It is part of vanilla PostgreSQL, needs no extra moving parts, and works extremely well for the vast majority of use-cases. Start there.

If you outgrow it - typically because you need better relevance ranking (BM25) or more scalability - you don’t have to leave PostgreSQL either. There are extensions that pick up exactly where the built-in search ends:

-   [pg\_textsearch](https://github.com/timescale/pg_textsearch) by Timescale / TigerData - BM25 ranking as a PostgreSQL extension.
-   [ParadeDB / pg\_search](https://github.com/paradedb/paradedb) - Elasticsearch-grade search inside PostgreSQL, built on Tantivy ([docs](https://docs.paradedb.com/)).

That’s the beauty of it: You can start with plain vanilla PostgreSQL and seamlessly move to more advanced techniques and third-party extensions once - and only if - you actually need them.

The pros and cons of full-text search on PostgreSQL are discussed very nicely in [this LinkedIn discussion](https://www.linkedin.com/feed/update/urn:li:activity:7495362603527909377/) - recommended reading before you decide.

More on the topic: [https://www.postgresql.org/docs/current/textsearch.html](https://www.postgresql.org/docs/current/textsearch.html)

### PostgreSQL replaces MongoDB: Excellent Json Support

PostgreSQL has excellent support for [storing and querying(!) json](https://www.postgresql.org/docs/current/datatype-json.html). It also features an index type (GIN) that makes these operations blazingly fast. Is there a need for MongoDB any more?.

The Guardian also wrote an excellent article how they [switched from Mongo to PostgreSQL](https://www.theguardian.com/info/2018/nov/30/bye-bye-mongo-hello-postgres). Thanks for sharing [Jan-Otto](https://www.linkedin.com/feed/update/urn:li:activity:7163447276114317313?commentUrn=urn%3Ali%3Acomment%3A%28activity%3A7163447276114317313%2C7163615175755874304%29&dashCommentUrn=urn%3Ali%3Afsd_comment%3A%287163615175755874304%2Curn%3Ali%3Aactivity%3A7163447276114317313%29)! Hazel also [wrote a nice piece on jsonb](https://challahscript.com/what_i_wish_someone_told_me_about_postgres#jsonb-is-a-sharp-knife) and what to take into account when using it.

### PostgreSQL replaces Kafka and RabbitMQ: PostgreSQL as a queue

Events, queues and persistent logs are getting more and more important in today’s software systems. Systems like Kafka, RabbitMQ, SQS and others provide that functionality. But maintaining them is annoying, custom and you need the skillset.

The good news: You can just use PostgreSQL. The magic comes from

-   SELECT .. FOR UPDATE
-   SELECT .. SKIP LOCKED

Using these SQL features you can effectively use a table as queue. Either in a persistent fashion with a cursor and many consumers, or in a read-once fashion.

The article at [crunchydata explains this concept very well](https://www.crunchydata.com/blog/message-queuing-using-native-postgresql).

My tip: Start with PostgreSQL as a queueing system. Only when that does no longer perform well switch to other systems like Kafka, RabbitMQ or SQS. You’ll be surprised how well PostgreSQL works.

### PostgreSQL Replaces Clickhouse: High Volume Time Series Data

Time series data is special. Often you get many data points in a very short amount of time. And then you have to aggregate the data frequently, doing some statistics on it and so on.

There are specialized software systems like Clickhouse (amazing by the way…). But you can also use a plugin for PostgreSQL that allows you to do (nearly) the same: [Timescale](https://github.com/timescale/timescaledb).

I’ve used Timescale and can recommend it. The good news is that you can continue using PostgreSQL - even for high volume data easily. No need to learn and maintain something new.

### PostgreSQL as Vector Database for AI Workflows

Timescale lately released the [pgvector extension](https://docs.timescale.com/use-timescale/latest/extensions/pgvector/?ref=timescale.com), that turns your PostgreSQL into a vector database. This allows you to use the tech you already know for indexing and retrieval of relevant data. That’s an essential part of AI LLM workflows.

Timescale also recently announced [pgai](https://github.com/timescale/pgai) that includes pgvector, but also a lot of other nice extensions that make it super simple to index data, call LLM models and retrieve data based on similarity.

### PostgreSQL Replaces Redis: Non-Persistent High Performance Caching

Caching is important. Most applications use something like Redis as a cache to get information like sessions and more quickly. A cache can by definition lose data and can be regenerated from the original source.

But. Why use Redis when PostgreSQL can be tuned to be as fast (in most usecases) as a Redis cache? The secret is using an UNLOGGED table. You can even emulate Redis’ automatic expire by a trigger. [A lot has been written about this](https://martinheinz.dev/blog/105) - I can just recommend trying it out.

### PostgreSQL Replaces File System: For Raw Data

For one of my clients we had to read and write a huge amount of small pieces of binary encoded information. We initially thought that doing this via the file system was the fastest way to do so.

After some performance checks it became clear that PostgreSQL was even faster than reading from the file system for our use-case. PostgreSQL uses the file system very efficiently for its data - and it adds a lot of caching and efficient reading and writing strategies that can outperform writing and reading raw data on a file system.

We used [Flatbuffers](https://github.com/google/flatbuffers) to store the data in a blob column. Data was then de-serialized on the client. You might want to try that approach as well.

### PostgreSQL Replacing Your Graph Database

Hierarchical data can be managed in SQL via recursive queries. That’s ok, but also super-hard to read, maintain and debug. Not even speaking of performance.

The better way is the [LTREE datatype of PostgreSQL](https://www.postgresql.org/docs/current/ltree.html). It helped me not only once to implement hierarchical tag structures. Easy to read, maintain and blazingly fast.

### PostgreSQL as Replacement for GraphDB and Neo4J

But what if you need a _real_ graph - not just a tree? Nodes, edges, properties, and queries that traverse relationships in every direction? That’s usually the moment someone suggests adding Neo4j to the stack. And with it: another system to run, another backup strategy, another data sync.

You don’t have to. [Apache AGE](https://age.apache.org/) (“A Graph Extension”) turns PostgreSQL into a graph database. It’s an Apache Software Foundation top-level project and it implements [openCypher](https://opencypher.org/) - the same query language you’d use in Neo4j. The best part: graph queries and plain SQL live in the _same_ database and can be combined in a single statement.

```sql
SELECT * FROM cypher('my_graph', $$
    MATCH (a:Person)-[:WORKS_AT]->(c:Company)
    RETURN a.name, c.name
$$) AS (person agtype, company agtype);
```

My tip - the same one as for queueing: Start with PostgreSQL. Model your graph with AGE (or with LTREE if a hierarchy is all you need) and only reach for a dedicated graph database if you really hit the limits. Your data stays in one place, transactional and consistent with the rest of your application.

More on the topic: [Apache AGE documentation](https://age.apache.org/age-manual/master/index.html) and the [source on GitHub](https://github.com/apache/age).

### PostgreSQL Replacing Your Microservice

Most of the “microservices” these days are only about models, getting data from a database and returning json to the client.

But you know what? PostgreSQL can turn any query into a Json result. That effectively replaces your server middleware. There are Pros and Cons to this approach, but it shows the capabilities of PostgreSQL. The amazing [Lukas Eder wrote about the topic](https://blog.jooq.org/stop-mapping-stuff-in-your-middleware-use-sqls-xml-or-json-operators-instead/) - not PostgreSQL specific - but everything mentioned there is very well doable in PostgreSQL as well

### PostgreSQL - Replacing your Playstation 5

Well. Some enthusiast implemented [Tetris as Common Table Expressions in pure SQL](https://github.com/nuno-faria/tetris-sql/blob/main/game.sql). Crazy. And maybe not to be taken too seriously.

## Conclusion

The list above is not very exhaustive. PostgreSQL is a very flexible piece of software. And it can be extended with plugins to do more and more.

You need simplicity if you want to move fast. If you come across a new requirement always ask: Can’t PostgreSQL do this? And do we really need that shiny new technology X?

PostgreSQL might not be the answer to everything - but it is the answer to a lot more than you might think!