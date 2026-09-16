-- The SQLite mirror of postgresql/V27__fk_indexes.sql. See that file for what the indexes are for.
--
-- SQLite enforces no foreign key here, so none of these answers a referential check; they exist so the
-- two dialects stay schema-identical.
CREATE INDEX idx_words_created_by             ON words(created_by);
CREATE INDEX idx_word_translations_created_by ON word_translations(created_by);
CREATE INDEX idx_groups_created_by            ON groups(created_by);

CREATE INDEX idx_word_tag_pairs_translation    ON word_tag_pairs(translation_word_id);
CREATE INDEX idx_game_play_answers_word        ON game_play_answers(word_id);
CREATE INDEX idx_game_play_answers_translation ON game_play_answers(translation_word_id);
CREATE INDEX idx_game_play_words_word          ON game_play_words(word_id);
CREATE INDEX idx_game_play_words_translation   ON game_play_words(translation_word_id);
