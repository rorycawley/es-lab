# Lab 27: search

Twenty-seven labs and nothing has ever been *found*. Every query so far fetches one row by primary key: `get-product`, `get-order`. The moment a person needs to type words into a box, a familiar conversation starts, and it usually ends with a second datastore.

This lab is the argument for having that conversation later. **The burden of proof is on the second datastore**, and Postgres carries more of the load than most teams check before leaving.

```bash
bb demo     # starts a real Postgres with Testcontainers
```

```text
  Words, not substrings.
    flavours              vanilla
    creamy                vanilla
    anill                 nothing

  A name beats a mention.
    pistachio             pistachio, chocolate

  A misspelling is a suggestion, not a result.
    pistacio              nothing — did you mean pistachio?
```

## A search index is a projection

There is no new idea here, only a new data structure. [Lab 9](../lab9) defined a read model as a fold you already did, pointed at a different question, derived from facts and disposable because it holds nothing they do not. A search index is exactly that:

| | lab 9's projection | a search index |
|---|---|---|
| derived from | the event log | the retained source text |
| answers | *which flavour sells best?* | *which products mention salt?* |
| rebuilt by | replaying from position 0 | re-analysing the corpus |
| may be deleted because | it holds nothing the events don't | it holds nothing the text doesn't |

Almost every way search goes wrong is a way of forgetting one of those rows. So the schema states it in DDL rather than in a comment:

```sql
description     TEXT NOT NULL DEFAULT '',
search_document tsvector GENERATED ALWAYS AS (
  setweight(to_tsvector('english', coalesce(product_name, '')), 'A') ||
  setweight(to_tsvector('english', coalesce(description,  '')), 'B')
) STORED
```

`description` is the retained source. `search_document` is derived from it by the database and written by nothing — no trigger, no application code, no nightly job. `describe_product.clj` does not contain the string `search_document`, and a fitness test says so. Change the description and the index simply agrees; drop the index entirely and the answers stay correct, only slow. Both are asserted in `search_index_test.clj`.

## The configuration is the fold version

`to_tsvector('english', …)` is two arguments, and it has to be. Postgres rejects the one-argument form in a generated column, because it depends on the `default_text_search_config` session setting and an index whose contents vary with a GUC could not be dumped and restored.

That refusal is doing something more interesting than validation. Naming the configuration makes the expression immutable — and makes `'english'` **part of this index's identity**. [Lab 17](../lab17) made the same point about snapshots: a cached fold records the version of the fold that produced it, because changing `evolve` can invalidate cached state without any event changing. Change your text search configuration and every stored vector is stale in exactly that way, and nothing about the rows has changed.

```clojure
;; the same row, the same word, two configurations
(is (= ["vanilla"] (names (catalog/search catalog {:query "flavours"}))))  ; english
(is (false? (:matched row)))                                              ; simple
```

Which is the whole reason the corpus is retained beside the index rather than replaced by it. A `tsvector` is **lossy** — stop words are gone, words are stems, and `'creami'` is not a word anybody typed. You cannot re-analyse what you did not keep.

## `LIKE` is not search, and it is two separate defects

The implementation everyone writes first ships in `dev/naive_search.clj` so it can be measured, in the spirit of [lab 0](../lab0)'s persistence-shaped model and [lab 16](../lab16)'s three designs.

**It matches letters, not words.** `LIKE '%anill%'` finds vanilla, which nobody asked for. `LIKE '%flavours%'` does not find a description saying *flavour*, which somebody did. No index would fix either — they are properties of substring matching.

**And it has no index it could use.** This is the part worth measuring carefully, because `EXPLAIN` answers a narrower question than *is it fast*. It says which options the planner had and which it took, and those two claims have very different stability. Whether the planner *chooses* an index depends on table statistics and will drift as data grows. Whether an index exists that it *could* choose does not.

So the assertion is the second one, and the probe is to forbid the sequential scan and ask again:

```text
                         chosen                    forbidden to scan
  search_document @@ q   GIN · product_search_idx   GIN · product_search_idx
  LIKE '%szechuan%'      Seq Scan                   Seq Scan
```

Over 50,000 products, told it may not scan the table, `LIKE` scans it anyway. There is no index in the database it could use, at any size, for any term. That is structural rather than a tuning difference, and it is the one thing here that cannot drift. `search_plan_test.clj` asserts it; `bb demo` prints the left-hand column, where an observation that changes with the data costs nothing.

The corpus matters. A plan assertion over three rows proves nothing — Postgres will scan a tiny table whatever indexes exist, and it is right to.

## The one you hand a person

```clojure
websearch_to_tsquery('english', ?)
```

It is the only query parser that **cannot raise a syntax error**, which makes it the only one safe to hand a string a user typed. `to_tsquery` turns a stray bracket into a 500. It also gives that user the syntax they already know from every search box they have used: bare words are ANDed, `"quoted words"` must be adjacent, `OR` offers a choice, and a leading `-` excludes.

```text
  "sea salt"            pistachio
  "salt sea"            nothing
  bitter -vanilla       chocolate
  ((                    nothing
```

That last row is a passing test, not a caught exception.

## Ranking is not a business rule

`setweight` puts the name in band A and the description in band B, so a product *called* pistachio outranks one that merely mentions pistachio. `ts_rank_cd` scores cover density; `ts_headline` returns the matching line with the hit marked.

None of that is a rule in [lab 0](../lab0)'s sense. Its criterion was that an attribute which cannot change any answer is not part of the model — and ranking changes the *order* of an answer, never its truth. Reweight every band and the set of matching products is identical. That is why weights live in the query slice beside the SQL, and why a business rule expressed as a ranking coefficient is a rule nobody can test.

## Typos are the actual reason people leave

Full-text search compares lexemes. `pistacio` produces no lexeme that `pistachio` produces, so it matches nothing, and this is the moment somebody says the word Elasticsearch.

```sql
CREATE INDEX product_name_trgm_idx
  ON catalog.product USING GIN (product_name gin_trgm_ops);
```

`pg_trgm` compares three-letter runs rather than words, so `pistacio` and `pistachio` are 0.58 similar and the default threshold is 0.3. It is a trusted extension, so no superuser is needed.

What matters is what the slice does with it. Trigrams have no idea what the user meant, so the response is a different response:

```clojure
{:found        [...]}   ; lexemes matched
{:did-you-mean [...]}   ; letters matched — a question, not an answer
{:no-matches   query}
```

`:no-matches` is not a failure mode to collapse into an empty `:found`. It is the metric a search feature lives or dies by, and [lab 26](../lab26)'s counter already groups by outcome — so adding two words to `outcome-of` produced a search-quality dashboard with no new instrumentation.

## The index lives with the data it indexes

Catalog searches `catalog.product`. Ordering searches `ordering.orders`. Neither is a separate Search module, and there is no shared index.

This follows [lab 25](../lab25)'s rule that a slice owns its SQL, and it is the cheapest thing that works: no contract to publish, no inbox to claim, no projection to rebuild, no lag, and no second copy of anything. It also means [lab 25](../lab25)'s database boundary keeps holding — a cross-schema search is not forbidden by convention, it is refused by Postgres.

**And it cannot do the thing people ask for.** One search box over two owners returns two lists:

```text
  catalog   2 product(s): pistachio, chocolate
  ordering  1 order(s)
```

There is no third list, because no single index contains both, and two `ts_rank_cd` scores from two tables are not comparable — cover density is relative to its own document set. A fitness test forbids any namespace but the composition root from holding both module APIs at once, so this cannot be quietly worked around inside a module.

What would earn a Search module is a concrete need for one ranked list across owners, or an index too expensive to keep in the write path. Then it becomes a third module with its own schema, fed by both modules' public contracts through an outbox — inheriting lag, duplication and a rebuild story it does not have today. That is a trade, not an upgrade, and [lab 25](../lab25)'s advice applies unchanged: refactor under evidence.

## What is searchable is a decision

`ordering.orders` holds `customer_email`, legitimately — a receipt has to go somewhere. It is the obvious field to make searchable and it is the one that must not be:

```sql
search_document tsvector GENERATED ALWAYS AS (
  to_tsvector('english', coalesce(product_name, ''))
) STORED
```

[Lab 15](../lab15) sealed personal data so that destroying one key erases one subject. [Lab 24](../lab24) shaped it out of responses. [Lab 26](../lab26) kept it out of telemetry. A trigram index over this column undoes all three at once, by making partial-email fishing cheap and fast for anyone who can reach a search box. An operator who needs one order gets an exact-match lookup.

```clojure
(doseq [query ["ada" "ada@example.com" "example.com"]]
  (is (= query (:no-matches (ordering/search ordering {:query query})))))
```

The search box is also the one input a user fills with anything at all, including their own address while hunting for their own order — so the query string is **not** a span attribute. Lab 26's allow-list is only an allow-list if free text cannot walk through it. `es.query_length` and the outcome answer the operational questions; `redaction_test.clj` asserts the words themselves never leave.

## What this costs, and what it does not do

A GIN index is not free. Every insert and update maintains it, so search makes writes slower — a cost that does not disappear by moving the index to another system, it just moves to a place where it is also a network hop and a consistency problem. Postgres softens it with a pending list (`fastupdate`), which defers work to `VACUUM`; `corpus.clj` runs `VACUUM ANALYZE` after its bulk load, because statistics are what the planner reasons with and a bulk load that skips them leaves it estimating against a table it believes is empty.

Where Postgres genuinely stops: no distributed sharding of the index, no learning-to-rank or relevance tuning beyond weights and normalisation, no per-field analyzers beyond what a text search configuration expresses, synonyms and custom stemming require dictionary files on the server's filesystem, and a single `tsvector` is capped at about a megabyte. Those are real reasons to run a search engine. *We need typo tolerance* and *`LIKE` got slow* are not.

## Deferred

Accent folding: `unaccent` is STABLE rather than IMMUTABLE, so putting it in a generated column requires wrapping it in a function falsely declared immutable, and that footgun deserves its own treatment. Query-time synonym and stop-word dictionaries. Faceting and aggregation over results. Pagination beyond `LIMIT`, which needs a stable sort key because rank ties are not deterministic. `ts_headline` cost, which is why it is only ever computed for the rows already selected. Highlighting untrusted text safely — `ts_headline` output is not XSS-safe and this lab treats it as data. Search-as-you-type, which wants a different index again.

## What's next

Search was the last thing this system could do entirely by itself. [Lab28](../lab28) makes it talk to somebody else's: a payment provider and an email provider, behind ports, with an anticorruption layer at each edge and a webhook coming back the other way.

The connection is idempotency. Every lab from 12 onward has argued that at-least-once delivery makes a duplicate normal rather than exceptional, and every one of them could close the argument locally, with a database. Lab 28 is where that stops being enough — half the work happens on a machine you do not own — and where the honest answer turns out to depend on which provider you picked.

## Running it

```bash
bb check    # lint and formatting
bb test     # search, index properties, query plans, telemetry, redaction, boundaries
bb demo     # the search box above, and the planner over 50,000 rows; needs Docker
```
