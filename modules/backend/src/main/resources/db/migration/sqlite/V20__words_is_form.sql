-- The SQLite mirror of postgresql/V20__words_is_form.sql. See that file for what the column is for.
ALTER TABLE words ADD COLUMN is_form BOOLEAN NOT NULL DEFAULT 0;

UPDATE words SET is_form = 1
WHERE EXISTS (SELECT 1 FROM word_forms WHERE word_forms.form_word_id = words.id);

-- No `varchar_pattern_ops` counterpart here: SQLite has no operator classes, and its own LIKE is
-- case-insensitive by default, so no index serves the prefix either way. These exist to keep the two
-- schemas the same shape; the tests that run on them care about answers, not plans.
CREATE INDEX idx_words_main_rank       ON words(language, frequency_rank)                 WHERE is_form = 0;
CREATE INDEX idx_words_main_search     ON words(language, text_search)                    WHERE is_form = 0;
CREATE INDEX idx_words_main_pos_search ON words(language, part_of_speech, text_search)    WHERE is_form = 0;
CREATE INDEX idx_words_form_rank       ON words(language, frequency_rank)                 WHERE is_form = 1;
CREATE INDEX idx_words_form_search     ON words(language, text_search)                    WHERE is_form = 1;
CREATE INDEX idx_words_form_pos_search ON words(language, part_of_speech, text_search)    WHERE is_form = 1;

-- The Postgres mirror also rebuilds V13's `idx_words_search` with the pattern operator class. There is
-- nothing to rebuild here: the index already has the only form SQLite offers.
