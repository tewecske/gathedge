-- Two kinds of import-made pair, where `word_tag_pairs.exact` was one.
--
-- `exact` meant "a bulk import matched this", and both import paths set it -- the free-text one, which
-- can only mark a pair `word_translations` already links, and the tabular one, which writes the pair the
-- reader's row asserts whether or not the dictionary agrees. One badge for both said "exact" about a
-- pair nothing had checked, which is what this column separates:
--
--   ''          the reader marked the pair by hand (what every non-imported row already was)
--   'verified'  both words were in the dictionary and already each other's translation
--   'paired'    the import wrote the pair itself, from a row that put the two cells on one line
--
-- `''` rather than NULL for the same reason `words.gender` uses it: absent is one of the values, not the
-- absence of one. The backfill can only guess -- an old `exact = TRUE` row carries no record of which
-- path wrote it -- so it reads as 'verified', the meaning the old badge claimed.
ALTER TABLE word_tag_pairs ADD COLUMN match_kind VARCHAR(16) NOT NULL DEFAULT '';
UPDATE word_tag_pairs SET match_kind = 'verified' WHERE exact = TRUE;
ALTER TABLE word_tag_pairs DROP COLUMN exact;
