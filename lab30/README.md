# Lab 30: multilingual names are not one string

Lab 27 built an English search projection. That design stops being sufficient
when a register must preserve names filed in French, German, and Italian while
letting a user search in any of them.

**The one idea: a filed name is one authoritative value, but searching it
requires several deliberately lossy projections, each built for one lookup
job.**

```bash
bb demo     # PostgreSQL 18 with real Unicode folding and indexes
```

```text
fr   CH-020.3.000.002-2          registration -> [Société Générale SA]
it   Strasse 1 SA                exact-name  -> [Straße 1 SpA]
de   Societe Generale            phrase      -> [Société Générale AG]
fr   Verwaltung                  phrase      -> [Vermögensverwaltungsgesellschaft Sàrl]
de   Muler                       fuzzy       -> [Bäckerei Müller und Söhne GmbH]
```

The example is a Registry module for corporate entities rather than another
field on Catalog. The language problem belongs to this business capability,
alongside its legal names and registration identifiers. It is still a vertical
slice: `Register entity` owns its write and `Search registered entities` owns
its lookup cascade, both behind one public module API.

## The source and the projections

One column cannot honestly do four incompatible jobs:

| value | derivation | job | information deliberately lost |
|---|---|---|---|
| `name` | filed value, NFC only | legal display and export | nothing |
| `name_ci` | Unicode `casefold(name)` | exact and prefix lookup | case |
| `name_key` | NFC, casefold, unaccent, punctuation fold | trigram typo matching | case, accents, punctuation |
| `name_tsv` | French, German, and Italian analysis | word and phrase search | original spelling and stop words |

`german_parts` is a versioned helper for `name_tsv`, not another public name.
Every value after `name` is derived and disposable. The retained filed name is
enough to rebuild them.

```sql
name       TEXT NOT NULL CHECK (name IS NFC NORMALIZED),
name_ci    TEXT GENERATED ALWAYS AS (casefold(name)) STORED,
name_key   TEXT GENERATED ALWAYS AS (registry.search_key(name)) STORED,
name_tsv   TSVECTOR GENERATED ALWAYS AS
             (registry.name_document(name, german_parts)) STORED
```

The schema does not ask one approximation to impersonate another. A `tsvector`
can stem words but cannot find arbitrary spelling mistakes. A trigram key can
find a typo but cannot understand a phrase or rank a linguistic match. A
punctuation-folded key cannot reproduce the legal name it destroyed.

## The database locale is part of the algorithm

The stock PostgreSQL container initializes its database with a libc locale.
Under that provider, the probe for this lab returned:

```sql
SELECT casefold('Straße');  -- straße
```

That is lowercase, not full Unicode case folding. Lab 30 initializes the
cluster deliberately:

```text
--locale-provider=builtin --builtin-locale=PG_UNICODE_FAST
```

Now the same call returns `strasse`. `normalization_test.clj` asserts the
encoding, locale provider, locale name, and result against the exact
`postgres:18.4-alpine` image. A deployment that silently changes any of those
has changed the search algorithm and must rebuild its derived data.

This is why Clojure does not compute `name_key`. A JVM default locale, an ICU
version, and PostgreSQL's `unaccent.rules` are three opportunities for the
write path and query path to disagree. Both instead invoke the same database
function.

## The fold, in the order it must happen

```text
NFC -> Unicode casefold -> unaccent -> delete apostrophes
    -> replace other punctuation with spaces -> collapse whitespace
```

Order matters. Case folding can change string length—`ß` becomes `ss`—and may
change normalization. Typographic `’` and straight `'` are different code
points that NFC does not reconcile. Apostrophes are deleted so `L’Oréal` and
`LOreal` converge; other punctuation becomes a word boundary.

```text
L'Oréal Suisse SA         -> loreal suisse sa
Bäckerei Müller & Co.     -> backerei muller co
Straße 1 AG               -> strasse 1 ag
Società Anonima d'Italia  -> societa anonima ditalia
ÉTAT / État               -> etat / etat
```

The guide said `unaccent` was immutable. The lab queried `pg_proc` instead of
trusting that statement. PostgreSQL 18.4 reports:

```text
casefold                 i  immutable
public.unaccent          s  stable
registry.f_unaccent      i  immutable
```

The wrapper is a deliberate promise made by this system, not a fact discovered
about the extension. It fixes the dictionary name and search path, and it puts
the rebuild obligation here: changing or upgrading the unaccent rules means
recomputing the generated keys and rebuilding their indexes.

## Full-text search in three languages

Registry defines one configuration per supported language by placing
`unaccent` before PostgreSQL's French, German, or Italian Snowball stemmer.
Every name is indexed under all three configurations:

```sql
to_tsvector('registry.fr', name) ||
to_tsvector('registry.de', name) ||
to_tsvector('registry.it', name)
```

Indexing only under `filing_lang` is smaller, but it assumes the searcher's
language is the filing language. At register scale, recall is the user-facing
requirement worth spending index space on. The query is likewise parsed under
all three configurations and the three `tsquery` values are ORed together.

`websearch_to_tsquery` remains the parser from Lab 27: a human can type quotes,
minus signs, or malformed punctuation without turning the search box into a
SQL syntax error.

## German compounds are the honest hard part

PostgreSQL's German Snowball stemmer changes suffixes; it does not decompose:

```text
Vermögensverwaltungsgesellschaft
```

So a query for `Verwaltung` cannot match the original lexeme. PostgreSQL can
use an Ispell/Hunspell dictionary for basic compound splitting, but the stock
image ships no dictionary files, and available operating-system dictionaries
become another unversioned input to the index.

This lab takes the guide's fallback path: `registry/german.clj` is a pure,
word-list-based splitter run at write time. It derives:

```text
vermögen verwaltung gesellschaft
```

Those parts are fed through the German text-search configuration and stored
with `search_version = 1`. `rebuild-search!` re-derives them from every retained
filed name, and a test deletes the parts, demonstrates the missed query, then
rebuilds and recovers it.

The small lexicon is intentionally incomplete. It proves the seam, not German
linguistic coverage: `Donaudampfschifffahrtsgesellschaft` deliberately remains
unsplit. A real register must build and measure a jurisdiction-specific
lexicon, or ship a pinned and verified Hunspell dictionary. Hiding that limit
behind a generic “German analyzer” would make the lab look more complete and
the system less honest.

## Legal form is not part of the name

`Muster AG`, `Muster SA`, and `Muster SpA` can be three presentations of one
entity, not three indexed names. Registry stores:

```clojure
{:name "Muster" :legal-form :plc :filing-language :de}
```

The query boundary tolerates a known suffix by removing it before lookup. The
response renders the structured form in `:ui-language`:

```text
de -> Muster AG
fr -> Muster SA
it -> Muster SpA
```

That prevents short, common tokens such as `AG` and `SA` from polluting every
trigram and text vector. Filing language and UI language remain different
facts.

The guide uses a database-generated bigint. Lab 30 keeps the repository's Lab
4 rule instead: the UUID identifying an entity exists before persistence, so
the object is fully identified from birth and identity allocation is not
coupled to one database adapter.

## The lookup cascade

The query handler does not build one opaque relevance formula. It runs a
ladder and stops at the first non-empty rung:

```text
1. registration number or EUID   exact B-tree
2. exact folded name             B-tree
3. folded name prefix            B-tree text_pattern_ops
4. multilingual phrase          GIN tsvector
5. fuzzy word extent             GIN pg_trgm
```

The response names the rung with `:registration`, `:exact-name`, `:prefix`,
`:phrase`, or `:fuzzy`. That makes behavior explainable to a user and makes
the distribution observable without recording the personal free text they
typed.

Fuzzy lookup uses `strict_word_similarity` and `<<%`, not whole-string `%`:

```sql
registry.search_key(?) <<% name_key
```

That is why `Muler` can find `Bäckerei Müller und Söhne` despite the very
different total lengths. A role-level threshold of `0.6` makes the tuning
choice explicit. Queries shorter than three folded characters never reach the
trigram rung; they contain too little signal and can cause broad, expensive
scans.

Each result is ordered with a fixed ICU collation selected from a closed map of
`fr-x-icu`, `de-x-icu`, and `it-x-icu`. A collation name is SQL syntax and
cannot be a user parameter, so accepting an arbitrary locale string here would
be an injection bug as well as an unsupported-language promise.

## What the tests prove

The suite follows the testing split established in Lab 21:

| test type | target | examples |
|---|---|---|
| pure unit | domain-adjacent derivation | German compound splitting and legal-form rendering |
| behavior / use case | Registry's public API | identifier, exact, prefix, phrase, fuzzy, removal, and closed validation |
| adapter / integration | PostgreSQL 18 | Unicode locale, generated keys, language configs, rebuilds, and index plans |
| architecture fitness | source boundaries | Malli at the public edge, separate slices, database-owned folding |

The test corpus includes NFC and NFD spellings, `ß`/`ss`, straight and
typographic apostrophes, French all-caps accent dropping, Italian punctuation,
a German compound, a typo inside a long name, translated legal forms, and a
single-character query. Search tests enter through `registry.api`; pure rules
remain directly testable without PostgreSQL.

Plan tests seed 10,000 rows, run `ANALYZE`, disable sequential scans, and prove
that the B-tree and GIN indexes can serve their respective operators. An early
version added a broad partial “live status” index copied from the guide. The
planner repeatedly used it to scan every live row and filter phrase/fuzzy
matches instead of using the selective GIN index. Removing that redundant
access path made the intended plan available. Indexes are workload decisions,
not decorations copied from a schema example.

## Deliberately outside this lab

- Jurisdiction is unresolved, so this lab does not claim BRIS or Swiss FADP
  compliance.
- Foreign-script BRIS names and Latin transliteration need a jurisdiction
  decision; ICU4J is not added speculatively.
- Person records are absent, so Daitch-Mokotoff phonetic lookup and its privacy
  implications are absent too.
- The German word list proves a replaceable seam, not production recall.
- No latency claim is made for five million rows. The plan is verified on a
  synthetic corpus; production needs representative names and query logs.
- Name availability and uniqueness are different use cases. This is lookup,
  optimized for recall, not a legal name-clearance decision.

## What's next

[Lab 31](../lab31) is about proving performance. It takes this lab's refusal to
claim latency from an index plan and builds the missing discipline: a declared
workload and budget, a correctness oracle, held-out inputs, paired trials, an
environment record, and an end-to-end experiment that can fail. The next
Registry-specific evidence still needs a representative corpus, each rung's
latency and fall-through rate, and a pinned German dictionary validated against
real filings.

## Running it

```bash
bb check    # lint and formatting
bb test     # 30 tests against real PostgreSQL 18; needs Docker
bb demo     # show which lookup rung answered; needs Docker
```

## Sources

- The repository-specific specification is
  [`registry-name-search-guide.md`](../registry-name-search-guide.md).
- PostgreSQL documents Unicode [`casefold` and `normalize`](https://www.postgresql.org/docs/18/functions-string.html),
  [generated-column immutability](https://www.postgresql.org/docs/18/ddl-generated-columns.html),
  [`unaccent`](https://www.postgresql.org/docs/18/unaccent.html),
  [Ispell/Hunspell compound handling](https://www.postgresql.org/docs/18/textsearch-dictionaries.html),
  and [`pg_trgm` strict-word operators and indexes](https://www.postgresql.org/docs/18/pgtrgm.html).
