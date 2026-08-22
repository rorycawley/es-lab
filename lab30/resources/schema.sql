CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE ROLE registry_module LOGIN PASSWORD 'registry-pass';
CREATE SCHEMA registry AUTHORIZATION registry_module;

SET ROLE registry_module;

CREATE TYPE registry.lang AS ENUM ('fr', 'de', 'it');

CREATE TYPE registry.legal_form AS ENUM (
  'plc', 'llc', 'lp', 'gp', 'coop', 'se', 'branch', 'other'
);

-- PostgreSQL 18.4 still marks unaccent(text) STABLE. This wrapper deliberately
-- promises immutability so the derived key can be stored and indexed. The
-- promise is ours: changing unaccent.rules requires rebuilding every key and
-- index produced through it.
CREATE FUNCTION registry.f_unaccent(txt text) RETURNS text
  LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
  RETURN public.unaccent('public.unaccent'::regdictionary, txt);

-- Accent removal belongs in full-text analysis too. Without it, Societe does
-- not find Société even though the trigram fallback eventually might.
CREATE TEXT SEARCH CONFIGURATION registry.fr (COPY = pg_catalog.french);
ALTER TEXT SEARCH CONFIGURATION registry.fr
  ALTER MAPPING FOR asciiword, asciihword, hword_asciipart,
                    word, hword, hword_part
  WITH public.unaccent, pg_catalog.french_stem;

CREATE TEXT SEARCH CONFIGURATION registry.de (COPY = pg_catalog.german);
ALTER TEXT SEARCH CONFIGURATION registry.de
  ALTER MAPPING FOR asciiword, asciihword, hword_asciipart,
                    word, hword, hword_part
  WITH public.unaccent, pg_catalog.german_stem;

CREATE TEXT SEARCH CONFIGURATION registry.it (COPY = pg_catalog.italian);
ALTER TEXT SEARCH CONFIGURATION registry.it
  ALTER MAPPING FOR asciiword, asciihword, hword_asciipart,
                    word, hword, hword_part
  WITH public.unaccent, pg_catalog.italian_stem;

-- NFC -> Unicode case folding -> accent removal -> apostrophe deletion ->
-- punctuation-to-space. One database implementation serves both indexed rows
-- and queries, so a JVM locale can never create a different key.
CREATE FUNCTION registry.search_key(txt text) RETURNS text
  LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
  RETURN btrim(regexp_replace(
    regexp_replace(
      registry.f_unaccent(casefold(normalize(txt, NFC))),
      '[' || U&'\2019' || U&'\02BC' || U&'\00B4' || U&'\0060' || U&'\2018' || '''' || ']',
      '', 'g'),
    '[^[:alnum:]]+', ' ', 'g'));

-- Index all three language analyses. It costs more space than choosing only
-- the filing language, but a search box cannot assume the user's language is
-- the language in which the company filed its name. The German parts are a
-- rebuildable write-time fallback for compound splitting.
CREATE FUNCTION registry.name_document(name text, german_parts text)
RETURNS tsvector
  LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
  RETURN setweight(to_tsvector('registry.fr'::regconfig, name), 'A') ||
         setweight(to_tsvector('registry.de'::regconfig, name), 'A') ||
         setweight(to_tsvector('registry.it'::regconfig, name), 'A') ||
         setweight(to_tsvector('registry.de'::regconfig, german_parts), 'B');

CREATE TABLE registry.entity (
  entity_id       UUID PRIMARY KEY,
  reg_no          TEXT NOT NULL UNIQUE,
  euid            TEXT UNIQUE,
  name            TEXT NOT NULL CHECK (name IS NFC NORMALIZED),
  legal_form      registry.legal_form NOT NULL,
  filing_lang     registry.lang NOT NULL,
  status          TEXT NOT NULL CHECK (status IN ('active', 'removed')),
  registered_on   DATE NOT NULL,
  removed_at      TIMESTAMPTZ,

  -- Derived and disposable. `german_parts` is supplied by the pure fallback
  -- splitter and records the version needed to rebuild it.
  german_parts    TEXT NOT NULL DEFAULT '',
  search_version  SMALLINT NOT NULL DEFAULT 1,
  name_ci         TEXT GENERATED ALWAYS AS (casefold(name)) STORED,
  name_key        TEXT GENERATED ALWAYS AS (registry.search_key(name)) STORED,
  name_tsv        TSVECTOR GENERATED ALWAYS AS
                    (registry.name_document(name, german_parts)) STORED
);

CREATE INDEX entity_name_ci_prefix_idx
  ON registry.entity (name_ci text_pattern_ops);

CREATE INDEX entity_name_key_trgm_idx
  ON registry.entity USING GIN (name_key gin_trgm_ops);

CREATE INDEX entity_name_tsv_idx
  ON registry.entity USING GIN (name_tsv);

ALTER ROLE registry_module SET pg_trgm.strict_word_similarity_threshold = 0.6;

RESET ROLE;
