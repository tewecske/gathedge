-- Which translation of a word the reader wants to be asked for, inside one of their tags.
--
-- A tag says "this word is in my vocabulary"; this table says "and *this* is the answer I want the
-- practice screen to check me against". The two are separate because a word usually has several
-- translations and only some of them are the sense being learned: `Haus` is worth practising against
-- `ház` and not against every gloss the dictionary lists under it.
--
-- A join table rather than a column on `word_tags`, because several translations may legitimately be
-- marked for the same (word, tag).
--
-- Rows are written in BOTH directions, for the reason `word_translations` is: every read is then a
-- filter on `word_id` with no union, and the practice screen can prompt either way round. Both words
-- also carry the tag — a pair whose answer is not itself collected is a question the reader could
-- never have the answer to — which is why `WordRepository.pairTranslation` writes the memberships and
-- the two pair rows as one transaction rather than leaving either half to a caller.
--
-- `tag_id` is the only reference to a per-account row, so this table reaches `users` only through
-- `tags` and cascades with it; `words` is shared by everybody and cascades directly.
CREATE TABLE word_tag_pairs (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    word_id             BIGINT NOT NULL REFERENCES words(id) ON DELETE CASCADE,
    tag_id              BIGINT NOT NULL REFERENCES tags(id)  ON DELETE CASCADE,
    translation_word_id BIGINT NOT NULL REFERENCES words(id) ON DELETE CASCADE,
    created_at          BIGINT NOT NULL,
    UNIQUE (word_id, tag_id, translation_word_id)
);

-- The listing reads a page's worth of pairs by `word_id`, which the UNIQUE index above already
-- answers as a prefix — hence no index of its own. This is the other direction, "every pair in this
-- tag", which is what the practice screen asks, and it mirrors `idx_word_tags_tag` for that reason.
CREATE INDEX idx_word_tag_pairs_tag ON word_tag_pairs(tag_id);
