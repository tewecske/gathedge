-- The SQLite mirror of postgresql/V25__pair_match_kind.sql. See that file for what the column is for.
-- `DROP COLUMN` needs no table rebuild here: `exact` is in no index, constraint or view.
ALTER TABLE word_tag_pairs ADD COLUMN match_kind TEXT NOT NULL DEFAULT '';
UPDATE word_tag_pairs SET match_kind = 'verified' WHERE exact = 1;
ALTER TABLE word_tag_pairs DROP COLUMN exact;
