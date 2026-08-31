-- The "main word only" listing filter, denormalized onto the row it filters.
--
-- A main word is one no `word_forms` row names as the *form* side, which the listing used to ask as a
-- correlated NOT EXISTS per row -- carried by both the page query and its count. The predicate is not a
-- property of the row that way, so the planner cannot combine it with the listing's own
-- `ORDER BY frequency_rank, text_norm LIMIT n`: it anti-joins the whole language partition and then sorts.
-- As a column it is an ordinary row predicate, and the partial index below serves that order directly.
--
-- The column is derived, not authoritative: `word_forms` stays the truth. It is maintained by the only two
-- writers of that table, `WordRepository.insertForms` and `.deleteWordForms`, each inside its own
-- transaction. Any future writer of `word_forms` must update it there too. Nothing deletes a `words` row,
-- so the ON DELETE CASCADE on `word_forms` can never strand a flag behind the app's back.
ALTER TABLE words ADD COLUMN is_form BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE words SET is_form = TRUE
WHERE EXISTS (SELECT 1 FROM word_forms WHERE word_forms.form_word_id = words.id);

-- Partial rather than plain indexes on `is_form`: each one covers the listing's own order or its search
-- predicate for the half of the table that listing looks at, so the filter is answered by the index that is
-- walked rather than rechecked per row. They mirror V2__words.sql's `idx_words_rank` and V13's
-- `idx_words_search`, which remain what the unfiltered listing uses.
--
-- The `is_form = TRUE` half has no caller yet: nothing in the app asks for forms alone. It is here so the
-- pair stays symmetric and a "forms only" listing needs no migration -- Postgres also keeps the two halves
-- cheaper to maintain than one whole-table index would be.
--
-- `varchar_pattern_ops` on every search index, because the database's collation is not `C`: under `en_US.utf8`
-- a plain b-tree cannot turn `LIKE 'hau%'` into a range scan, so the prefix is rechecked row by row against
-- everything the equality columns matched. With the pattern operator class the prefix becomes the index
-- condition itself. `language` and `part_of_speech` keep the default class -- they are compared with `=`.
--
-- Two search indexes per half, not one: `part_of_speech` sits between `language` and the prefix, so an index
-- carrying it cannot serve a search that does not filter on it. The listing's part-of-speech filter is
-- optional, and each shape needs the index whose leading columns it actually constrains.
CREATE INDEX idx_words_main_rank       ON words(language, frequency_rank)                                  WHERE is_form = FALSE;
CREATE INDEX idx_words_main_search     ON words(language, text_search varchar_pattern_ops)                 WHERE is_form = FALSE;
CREATE INDEX idx_words_main_pos_search ON words(language, part_of_speech, text_search varchar_pattern_ops) WHERE is_form = FALSE;
CREATE INDEX idx_words_form_rank       ON words(language, frequency_rank)                                  WHERE is_form = TRUE;
CREATE INDEX idx_words_form_search     ON words(language, text_search varchar_pattern_ops)                 WHERE is_form = TRUE;
CREATE INDEX idx_words_form_pos_search ON words(language, part_of_speech, text_search varchar_pattern_ops) WHERE is_form = TRUE;

-- The unfiltered listing's own search index, from V13__word_search_norm.sql, has the same collation problem
-- and is rebuilt here rather than edited there: that migration has run, so its file must not change. Same
-- name, same columns, same meaning -- only the operator class differs, so nothing else has to know.
DROP INDEX idx_words_search;
CREATE INDEX idx_words_search ON words(language, text_search varchar_pattern_ops);
