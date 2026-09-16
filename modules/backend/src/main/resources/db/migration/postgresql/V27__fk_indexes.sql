-- Index the foreign keys Postgres was scanning.
--
-- Postgres indexes the referenced side of a foreign key (the primary key) but never the referencing
-- side. Every check it runs on the child table -- a CASCADE, a SET NULL, or the RESTRICT check a plain
-- REFERENCES implies -- is therefore a sequential scan unless something else happens to index the
-- column. These eight columns had nothing. Each index only covers an existing constraint, so no query
-- in the application layer changes; these can only help.
--
-- Deleting one account SET NULLs over `words` (~48k rows) and `word_translations` (~33k), end to end.
-- `SessionReaper`'s guest sweep runs that on a timer.
CREATE INDEX idx_words_created_by             ON words(created_by);
CREATE INDEX idx_word_translations_created_by ON word_translations(created_by);
CREATE INDEX idx_groups_created_by            ON groups(created_by);

-- Deleting one word is one CASCADE check plus four RESTRICT checks, and the tag editor's bulk delete
-- pays that once per word. `word_tag_pairs.word_id` and `.tag_id` are already covered (the UNIQUE index
-- and `idx_word_tag_pairs_tag`); `translation_word_id` is the third column of that UNIQUE index, so it
-- is not a leftmost prefix and answers nothing.
CREATE INDEX idx_word_tag_pairs_translation    ON word_tag_pairs(translation_word_id);
CREATE INDEX idx_game_play_answers_word        ON game_play_answers(word_id);
CREATE INDEX idx_game_play_answers_translation ON game_play_answers(translation_word_id);
CREATE INDEX idx_game_play_words_word          ON game_play_words(word_id);
CREATE INDEX idx_game_play_words_translation   ON game_play_words(translation_word_id);
