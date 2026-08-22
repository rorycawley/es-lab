# Multilingual name lookup for a national corporate register
### PostgreSQL 18 + Clojure · French / German / Italian · 500k–5M records

Version 2. This replaces the general Unicode guide — the use case is now specific enough
that most of v1 was answering the wrong question.

Anything I couldn't verify against an authoritative source is flagged **[VERIFY]** with the
exact query to run.

---

## 0. What changed from v1, and why

| v1 said | v2 says | Why |
|---|---|---|
| Use `unaccent` only if you're sure | `unaccent` is **required** | French all-caps convention drops accents (`ETAT` / `État`), so both spellings exist in real filings |
| `unaccent()` isn't immutable, wrap it | It **is** marked immutable; wrap it anyway | I was wrong — see §5. Marked IMMUTABLE since 2013 |
| Uniqueness is the hard problem | **Recall** is the hard problem | It's a lookup, not a name-clearance gate |
| Worry about CJK segmentation | Dropped entirely | Three Latin-script languages |
| One name key | **Four** keys, different jobs | §3 |
| — | German compounding is the main risk | New. §6 |

---

## 1. Unresolved: which jurisdiction

There's a contradiction still to settle. BRIS covers the 27 EU member states plus Iceland,
Liechtenstein and Norway, and explicitly excludes Switzerland. But no BRIS participant has
French, German and Italian as its official languages:

- **Switzerland** — matches the languages exactly (plus Romansh), but is outside BRIS
- **Luxembourg** — French, German, Luxembourgish. In BRIS
- **Belgium** — Dutch, French, German. In BRIS

Almost everything below holds either way. The parts that don't:

| If Switzerland | If EU member state |
|---|---|
| No BRIS ingest; §8 is optional | §8 is mandatory, and foreign-script names arrive over it |
| No ß anywhere in native data | ß appears in German-language filings |
| Add Romansh as a 4th `regconfig` (falls back to `simple` — no Postgres dictionary exists) | Add the missing 4th language (Dutch or Luxembourgish) |
| GDPR via the Swiss FADP equivalent | GDPR directly |

Tell me which and I'll tighten §8 and the test corpus.

---

## 2. Foundations (condensed from v1)

These are unchanged and still non-negotiable. Short version:

```sql
CREATE DATABASE registry
  TEMPLATE template0 ENCODING 'UTF8'
  LOCALE_PROVIDER builtin BUILTIN_LOCALE 'PG_UNICODE_FAST';
```

1. **UTF8 or nothing.** Verify with `SHOW server_encoding`. Can't be changed later without
   dump/restore.
2. **Never `libc` collation.** A glibc upgrade silently reorders text indexes and breaks
   unique constraints. `builtin` and `icu` are versioned inside Postgres.
3. **NFC on write**, enforced by `CHECK (name IS NFC NORMALIZED)`. `café` has two byte
   representations and both will otherwise land in your register.
4. **Postgres `text` cannot hold `U+0000`.** Java strings can. Strip it at the boundary.
5. **Never retroactively rewrite a filed name.** It's a legal record. Derive keys from it
   instead (§3).

---

## 3. The four keys

One column per job. Only the first is legally meaningful; the other three are derived and
disposable — you can drop and rebuild them whenever the rules change.

| Column | Derivation | Used for |
|---|---|---|
| `name` | As filed, NFC only | Display, legal record, BRIS export |
| `name_ci` | `casefold(name)` | Exact lookup, autocomplete prefix |
| `name_key` | casefold → unaccent → fold punctuation → collapse space | Trigram fuzzy match |
| `name_tsv` | `to_tsvector(lang, name)` | Multi-word phrase search |

Plus, for person records only: `name_dm` (Daitch-Mokotoff codes, §7).

**Why four and not one:** each stage destroys information the next stage still needs.
`name_key` can't rank ("Bank" vs "Banque" both fold toward nothing useful), `name_tsv` can't
handle typos, `name_ci` can't handle accents. They're complements, not alternatives.

---

## 4. Schema

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS fuzzystrmatch;

CREATE TYPE lang AS ENUM ('fr', 'de', 'it');

CREATE TYPE legal_form AS ENUM (
  'plc',       -- AG   / SA    / SpA
  'llc',       -- GmbH / Sàrl  / Srl
  'lp',        -- KG   / SCS   / SAS
  'gp',        -- OHG  / SNC   / SNC
  'coop',      -- Gen. / Coop. / Soc. coop.
  'se',        -- Societas Europaea
  'branch',
  'other'
);

CREATE TABLE entity (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  reg_no        text NOT NULL UNIQUE,
  euid          text UNIQUE,              -- European Unique Identifier (BRIS)

  -- legal record: exactly as filed, NFC-normalised, never rewritten
  name          text NOT NULL CHECK (name IS NFC NORMALIZED),

  -- the legal form is NOT part of the name; it's structured
  form          legal_form NOT NULL,
  filing_lang   lang NOT NULL,

  status        text NOT NULL,
  registered_at date NOT NULL,
  removed_at    date
);
```

**Splitting the legal form out is the single highest-value schema decision here.** In a
trilingual state, `Muster AG`, `Muster SA` and `Muster SpA` may be the same company referred
to in three languages. If the suffix lives in the name string, a search for one never finds
the others, and `AG`/`SA` also pollute every trigram and tsvector in the table. Store the
bare name; render the suffix per the user's UI language.

### Derived search columns

```sql
ALTER TABLE entity
  ADD COLUMN name_ci  text GENERATED ALWAYS AS (casefold(name)) STORED,
  ADD COLUMN name_key text GENERATED ALWAYS AS (search_key(name)) STORED,
  ADD COLUMN name_tsv tsvector GENERATED ALWAYS AS (
    to_tsvector(ts_config_for(filing_lang), name)) STORED;
```

**[VERIFY]** whether `casefold()` is immutable enough for a generated column:
```sql
SELECT proname, provolatile FROM pg_proc WHERE proname IN ('casefold','unaccent');
-- 'i' = immutable (works in generated columns and indexes)
-- 's' = stable    (functional index only, via a wrapper)
```
The commitfest discussion for the feature explicitly describes using `CASEFOLD(t)` in a
unique expression index as the intended replacement for `LOWER(t)`, so I expect `'i'` — but
I couldn't confirm the catalog marking. If it comes back `'s'`, use plain functional indexes
instead of generated columns; everything else is unaffected.

### Indexes

```sql
-- exact and prefix (autocomplete)
CREATE INDEX entity_name_ci_btree ON entity (name_ci text_pattern_ops);

-- fuzzy
CREATE INDEX entity_name_key_trgm ON entity USING gin (name_key gin_trgm_ops);

-- phrase
CREATE INDEX entity_tsv ON entity USING gin (name_tsv);

-- filters that always accompany a search
CREATE INDEX entity_status ON entity (status) WHERE removed_at IS NULL;
```

At 5M rows expect roughly 1–2 GB for the trigram GIN and 0.5–1 GB for the tsvector GIN.
Budget RAM accordingly — GIN indexes that don't fit in `shared_buffers` + page cache are
where trigram search goes from 20 ms to 2 s.

---

## 5. The `search_key` fold

Order matters: NFC → casefold → unaccent → punctuation → whitespace. Each stage assumes the
previous one ran.

```sql
-- search_path-safe unaccent wrapper
CREATE FUNCTION f_unaccent(text) RETURNS text
  LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
  AS $$ SELECT public.unaccent('public.unaccent', $1) $$;

CREATE FUNCTION search_key(txt text) RETURNS text
  LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
AS $$
  SELECT btrim(regexp_replace(
    regexp_replace(
      f_unaccent(casefold(normalize(txt, NFC))),
      -- apostrophe family: delete, don't space
      '[' || U&'\2019' || U&'\02BC' || U&'\00B4' || U&'\0060' || U&'\2018' || ''''
          || ']', '', 'g'),
    -- everything else non-alphanumeric becomes a single space
    '[^[:alnum:]]+', ' ', 'g'
  ));
$$;
```

Worked examples — run these as your acceptance test:

```sql
SELECT search_key('L''Oréal Suisse SA');        -- 'loreal suisse sa'
SELECT search_key('Bäckerei Müller & Co.');     -- 'backerei muller co'
SELECT search_key('Straße 1 AG');               -- 'strasse 1 ag'   ← ß→ss via casefold
SELECT search_key('Società Anonima d''Italia'); -- 'societa anonima ditalia'
SELECT search_key('ÉTAT');                      -- 'etat'
SELECT search_key('État');                      -- 'etat'           ← same. This is the point
```

### Two decisions embedded here

**Apostrophes are deleted, not spaced.** `L'Oréal` → `loreal`, so it matches a user typing
`LOreal` or `Loreal`. The cost is that `d'Italia` → `ditalia` won't match someone typing
`d Italia`. Trigram matching is forgiving enough that deletion wins on balance, but this is
a genuine tradeoff — measure it against real query logs if you have them.

**Apostrophe variants must all be folded.** `L'Oréal` gets *filed* with U+2019 (typographic)
and *searched* with U+0027 (straight). Different code points; NFC will not reconcile them.
Same problem in the hyphen family (U+2010, U+2011, U+2013, U+2014) — the regex above catches
those under `[^[:alnum:]]`.

### Correction to v1
I said `unaccent()` isn't immutable and therefore can't be indexed. That was wrong — it was
marked IMMUTABLE in 2013. The marking is a convenient fiction (it depends on a mutable rules
file that Tom Lane pointed out has no hard-wired connection to the function), so the wrapper
above is still the right pattern, and **you must REINDEX if you ever edit `unaccent.rules`**.
But the reason is search_path safety and rules-file drift, not the volatility marking.

---

## 6. Full-text search: the German compounding problem

This is the biggest technical risk in the build. Prototype it before anything else.

### The problem
The default `german` config uses a Snowball stemmer. It stems suffixes but does **not**
decompose compounds. So:

```sql
SELECT to_tsvector('german', 'Vermögensverwaltungsgesellschaft');
-- 'vermogensverwaltungsgesellschaft':1     ← one lexeme
```
A user typing `Verwaltung` gets nothing. In a German-language company register, where
compounds are the norm rather than the exception, this is a recall disaster. French and
Italian don't have this problem; German alone drives the whole design.

### The fix: Hunspell
Ispell-format dictionaries support compound splitting, provided the affix file declares the
`compoundwords controlled` flag. Hunspell has sophisticated compound support; MySpell has
none at all.

```sql
-- files go in $SHAREDIR/tsearch_data/ as de_de.dict and de_de.affix
CREATE TEXT SEARCH DICTIONARY german_hunspell (
  TEMPLATE  = ispell,
  DictFile  = de_de,
  AffFile   = de_de,
  StopWords = german
);

CREATE TEXT SEARCH CONFIGURATION de_compound (COPY = german);
ALTER TEXT SEARCH CONFIGURATION de_compound
  ALTER MAPPING FOR asciiword, word, hword_part, hword_asciipart
  WITH german_hunspell, german_stem;
```

Verify before you build on it:
```sql
SELECT ts_lexize('german_hunspell', 'Wasserkraft');
-- want: {wasserkraft,wasser,kraft}
-- if you get {} or {wasserkraft}, compound splitting is NOT working

SELECT * FROM ts_debug('de_compound', 'Vermögensverwaltungsgesellschaft');
```

**Warning from the mailing lists:** several people have reported `hunspell-de-de` from the
Debian/Ubuntu packages returning empty or unsplit results where the Norwegian example in the
docs works fine. Budget real time for this and have a fallback plan (§6.1) in case the
dictionary doesn't cooperate.

### 6.1 Fallback if Hunspell won't split
Generate the decomposition in Clojure at write time and append it to the tsvector:
```clojure
;; ICU4J BreakIterator won't decompound German either — you need a wordlist-based
;; splitter or a compound-aware analyser. This is a real build, not a library call.
```
I don't have a verified off-the-shelf JVM German decompounder to recommend. Apache Lucene
ships `DictionaryCompoundWordTokenFilter` and `HyphenationCompoundWordTokenFilter`, which is
the usual answer, but pulling Lucene in just for this is heavy. **[VERIFY]** — worth a
spike before committing either way.

### Per-language config dispatch
```sql
CREATE FUNCTION ts_config_for(l lang) RETURNS regconfig
  LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
AS $$
  SELECT CASE l
    WHEN 'de' THEN 'de_compound'::regconfig
    WHEN 'fr' THEN 'french'::regconfig
    WHEN 'it' THEN 'italian'::regconfig
  END
$$;
```

Note this indexes each name under **one** language. A German-language filing searched with a
French query string won't match on the tsvector path — it falls through to trigram (§7).
If you want true cross-language coverage, index the same name under all three configs into
one tsvector (`to_tsvector('de_compound',name) || to_tsvector('french',name) || ...`). Three
times the index size, much better recall. At 5M rows I'd do it — the storage is cheap and
recall is the thing users actually notice.

---

## 7. The lookup cascade

Don't build one clever query. Build a ladder, stop at the first rung that returns enough.

```
1. Registration number / EUID   → exact, B-tree.  Most professional users have this.
2. Exact name                   → name_ci = casefold($1)
3. Prefix                       → name_ci LIKE casefold($1) || '%'    (autocomplete)
4. Phrase / multi-word          → name_tsv @@ websearch_to_tsquery(cfg, $1)
5. Fuzzy                        → name_key %  search_key($1)
6. Phonetic (persons only)      → name_dm && daitch_mokotoff($1)
```

Rungs 1–3 answer the large majority of real traffic and cost microseconds. Only pay for 4–6
when the cheap rungs come back empty.

### Rung 5 in detail
`pg_trgm` gives three similarity flavours, and picking the wrong one is the usual reason
fuzzy search "doesn't work":

| Operator | Function | Semantics |
|---|---|---|
| `%` | `similarity()` | Whole string vs whole string |
| `<%` | `word_similarity()` | Query vs the best-matching *extent* in the target |
| `<<%` | `strict_word_similarity()` | As above, but respecting word boundaries |

For a register, **`<<%` is usually what you want**: the user types `Müller` and the record is
`Bäckerei Müller und Söhne`. Plain `%` scores that pair terribly because the strings are very
different lengths; `<<%` finds the matching extent and ignores the surrounding length.

```sql
SET pg_trgm.strict_word_similarity_threshold = 0.6;   -- default 0.5

SELECT id, name, strict_word_similarity(search_key($1), name_key) AS score
FROM   entity
WHERE  search_key($1) <<% name_key
  AND  removed_at IS NULL
ORDER  BY score DESC
LIMIT  25;
```

One caveat from the docs: the `<->` distance-ordering formulation is efficient on **GiST**
but not GIN. If you find yourself wanting top-k nearest-neighbour ordering rather than
threshold filtering, you need a GiST index instead — so decide which access pattern you're
building before you pick the index type.

### Rung 6: phonetic, persons only
`soundex`, `metaphone` and `dmetaphone` don't work properly with multibyte encodings like
UTF-8; the docs say to use `daitch_mokotoff` or `levenshtein` instead. Daitch-Mokotoff is
markedly better for non-English names — six meaningful letters instead of four, ten possible
codes per letter group instead of seven, and it emits multiple codes when a letter group has
several plausible pronunciations.

That last property is exactly what you need for a trilingual register, where `Meier` /
`Meyer` / `Maier` / `Mayr` are the same family name.

```sql
ALTER TABLE person
  ADD COLUMN name_dm text[] GENERATED ALWAYS AS
    (daitch_mokotoff(search_key(surname))) STORED;

CREATE INDEX person_dm ON person USING gin (name_dm);

SELECT * FROM person
WHERE name_dm && daitch_mokotoff(search_key($1));
```
Use this as a **last** rung and label the results as phonetic matches in the UI. It is
deliberately over-inclusive; presenting its output as if it were an exact match will confuse
users and, for person data, is arguably a fairness problem.

**[VERIFY]** whether `daitch_mokotoff` is immutable enough for a generated column — same
`pg_proc` query as §4.

---

## 8. BRIS (if EU)

Under Directive (EU) 2017/1132 and Implementing Regulation (EU) 2015/884, member state
registers interconnect via a European Central Platform, surfaced through the e-Justice
portal, exchanging messages over CEF eDelivery AS4. Practical consequences for this design:

1. **`euid` is a first-class column**, not an afterthought. It's the cross-border join key.
2. **Foreign names enter your register.** Branches and cross-border merger records arrive
   from other member states, including Greek and Bulgarian ones. Your trigram index will
   therefore contain non-Latin scripts even though your official languages are all Latin.
   `search_key` handles them without erroring (they pass through `[^[:alnum:]]` intact), but
   `unaccent` won't transliterate them and no FTS config will tokenise them meaningfully.
3. **Consider a transliterated shadow key** for foreign-script names so a user typing Latin
   can find them:
   ```clojure
   (import '[com.ibm.icu.text Transliterator])
   (def to-latin (Transliterator/getInstance "Any-Latin; Latin-ASCII"))
   (.transliterate to-latin "Ελληνική Εταιρεία")  ;=> "Ellenike Etaireia"
   ```
   Store as `name_key_latin` and add it to the trigram search. This is the one place ICU4J
   is doing something the database genuinely can't.
4. **Encoding at the boundary.** The AS4/XML payload must declare UTF-8 and your serialiser
   must not silently substitute `?` for characters outside its charset. Test with a Greek
   company name end to end.

---

## 9. Clojure side

### Dependencies
```clojure
{:deps
 {org.clojure/clojure               {:mvn/version "1.12.0"}
  com.github.seancorfield/next.jdbc {:mvn/version "1.3.1002"}
  org.postgresql/postgresql         {:mvn/version "42.7.7"}
  com.ibm.icu/icu4j                 {:mvn/version "78.3"}
  com.zaxxer/HikariCP               {:mvn/version "6.2.1"}}}
```
Only `icu4j 78.3` is verified (Maven Central, March 2026). **Check the rest.**

### Do the folding in Postgres, not Clojure
v1 offered a choice. For this system it isn't one. If Clojure computes `search_key` and
Postgres computes the indexed `search_key`, any divergence — a different ICU version, a
different unaccent rules file, a JVM locale difference — silently produces queries that miss
rows. One implementation, in the database, called by both the write path and the query path.

The exception is §8's transliteration, which Postgres can't do without `icu_ext`.

### The JVM traps that still apply
```clojure
;; 1. clojure.string/lower-case uses the DEFAULT LOCALE. On a Turkish-locale JVM
;;    "I" → "ı" and lookups silently stop matching. Never use it for keys.
(.toLowerCase "AG" java.util.Locale/ROOT)   ; if you must fold in Clojure

;; 2. \w is ASCII-only. This rejects valid company names:
(re-matches #"^[\w\s]+$" "Bäckerei Müller")   ;=> nil
(re-matches #"(?U)^[\w\s]+$" "Bäckerei Müller") ;=> "Bäckerei Müller"

;; 3. count is UTF-16 units, not characters
(count "Müller")     ;=> 6  (NFC)
(count "Mu\u0308ller") ;=> 7  (NFD — same name, different count)

;; 4. sort is code-point order, not linguistic
(sort ["Zürich" "Ähre" "Bern"])  ;=> ("Bern" "Zürich" "Ähre")   ← wrong in all three languages
```
For #4, sort in Postgres with an ICU collation matching the user's UI language:
```sql
SELECT name FROM entity ORDER BY name COLLATE "de-CH-x-icu";
```

### JDBC
pgjdbc sets `client_encoding` to UTF8 itself and aborts the connection if it detects a
change. Don't set it, don't set `characterEncoding`, don't enable `allowEncodingChanges`.

---

## 10. Test corpus

Real strings, each breaking a specific thing. Put them in a fixture with expected
`search_key` output and expected search hits.

| String | Tests |
|---|---|
| `Straße 1 AG` / `Strasse 1 AG` | ß folding — must be one match, not two |
| `L'Oréal` (U+2019) vs `L'Oreal` (U+0027) | Apostrophe variants + accent |
| `ÉTAT` vs `État` vs `Etat` | French all-caps accent dropping |
| `Vermögensverwaltungsgesellschaft` | German compound split (§6) |
| `Société Générale` vs `Societe Generale` | Accent-insensitive match |
| `Müller` (NFC) vs `Müller` (NFD) | Normalisation before folding |
| `Meier` / `Meyer` / `Maier` / `Mayr` | Daitch-Mokotoff clustering |
| `Muster AG` / `Muster SA` / `Muster SpA` | Legal-form equivalence (§4) |
| `Bäckerei Müller und Söhne` searched as `Müller` | `<<%` vs `%` (§7) |
| `Fratelli D'Angelo S.r.l.` | Italian apostrophe + punctuated form |
| `Ελληνική Εταιρεία` (BRIS branch) | Foreign script survives ingest and index |
| `A` (single char) | Trigram degenerates — needs a minimum-length guard |
| `ab\u0000cd` | Postgres rejects NUL |
| `Zürich` / `Ähre` / `Bern` | Collated sort order per language |

The single-character case deserves attention: trigram search on a 1–2 character query is
both useless and expensive. Require 3 characters before rung 5 fires.

---

## 11. Performance expectations at 5M rows

Rough, for a machine with the GIN indexes resident in memory. **These are estimates, not
measurements** — benchmark on your own data.

| Rung | Expected |
|---|---|
| Reg no / EUID exact | < 1 ms |
| `name_ci` exact | < 1 ms |
| Prefix (autocomplete) | 1–5 ms |
| tsvector phrase | 5–30 ms |
| Trigram `<<%` | 20–150 ms |
| Daitch-Mokotoff | 10–50 ms |

Postgres alone is the right call at this scale. I'd resist adding Elasticsearch: keeping a
second store consistent with a statutory register is a meaningful ongoing liability, and the
gain at 5M short strings is small.

Two knobs that matter more than they look:
- `pg_trgm.strict_word_similarity_threshold` — the difference between 200 results and 20
- `work_mem` — GIN bitmap scans that spill to disk are the usual cause of a slow trigram query

---

## 12. Order of work

1. Confirm encoding and collation provider (§2). Blocking; can't be fixed later cheaply.
2. **Spike the German Hunspell dictionary** (§6). It's the highest-risk unknown, and the
   answer changes the FTS design. Do this before writing schema.
3. Run the `pg_proc` volatility checks (§4) — decides generated columns vs functional indexes.
4. Build `search_key` and its acceptance tests (§5).
5. Schema + indexes (§4).
6. Cascade rungs 1–3 (§7). Ship it. These handle most traffic.
7. Add rungs 4–6 with the test corpus (§10) as the acceptance gate.
8. BRIS ingest and transliteration (§8), if applicable.

---

## 13. Open questions

**For you:**
- Jurisdiction (§1) — determines BRIS scope, ß handling, and the fourth language.
- Do person records include beneficial owners? If so, BORIS applies as well as BRIS, and
  the access-control model is stricter than for company data.
- Do you have query logs from the existing system? Real query distribution would settle the
  apostrophe question in §5 and the threshold in §7 far better than my judgement will.

**For your legal team, not me:**
- Whether the person-name search has GDPR constraints on bulk enumeration. Several EU
  registers have had rate limits and wildcard restrictions imposed after court challenges,
  and that shapes the API more than the schema does.

**Things I could not verify:**
- `casefold()` and `daitch_mokotoff()` volatility markings (§4, §7) — one query each.
- Whether `hunspell-de-de` actually decompounds under PG 18 (§6).
- A JVM German decompounder to recommend as fallback (§6.1).
- Current versions of next.jdbc, pgjdbc, HikariCP (§9).
