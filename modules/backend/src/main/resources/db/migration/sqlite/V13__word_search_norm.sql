-- Search becomes accent-insensitive: `text_search` is `text_norm` with accents folded off (see
-- `TextSearch.fold`), so typing "hau" also finds "häuser" and "o" finds "ő". `text_norm` is untouched
-- and stays the identity column the UNIQUE constraint and `findWord` use; only the search predicate
-- moves to this new column.
--
-- The backfill only needs to know the diacritics the dictionary actually contains: German and
-- Hungarian, the only two accented languages this feature covers.
ALTER TABLE words ADD COLUMN text_search VARCHAR(255) NOT NULL DEFAULT '';

UPDATE words SET text_search =
    REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
        text_norm,
        'á', 'a'), 'ä', 'a'), 'é', 'e'), 'í', 'i'), 'ó', 'o'), 'ö', 'o'), 'ő', 'o'), 'ú', 'u'), 'ü', 'u'), 'ű', 'u');

CREATE INDEX idx_words_search ON words(language, text_search);
