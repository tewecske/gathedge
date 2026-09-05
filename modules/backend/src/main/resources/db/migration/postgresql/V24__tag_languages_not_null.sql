-- Issue #31: a tag's language pair becomes mandatory.
--
-- Until now `source_language` / `target_language` were nullable and `WordRepository.setTagLanguages`
-- filled them lazily from the first pair added. From here a tag is created with its pair, the pair
-- is editable only while the tag has no `word_tag_pairs` row, and the columns are `NOT NULL`. Every
-- existing row has to be given a pair first.
--
-- Backfill, in order of confidence:
--   1. from an existing practice pair -- the `word_id` side's language is the source, the
--      `translation_word_id` side's is the target (lowest pair id wins, the canonical direction the
--      editor already used);
--   2. else from any membership word's language, for the source only;
--   3. else `de` / `hu`, this deployment's default direction. A target still missing after step 1
--      falls through to `hu`, or `de` when the source is `hu`.

UPDATE tags t SET
    source_language = COALESCE(
        t.source_language,
        (SELECT w.language
           FROM word_tag_pairs p JOIN words w ON w.id = p.word_id
          WHERE p.tag_id = t.id
          ORDER BY p.id
          LIMIT 1),
        (SELECT w.language
           FROM word_tags m JOIN words w ON w.id = m.word_id
          WHERE m.tag_id = t.id
          ORDER BY m.id
          LIMIT 1)
    ),
    target_language = COALESCE(
        t.target_language,
        (SELECT w.language
           FROM word_tag_pairs p JOIN words w ON w.id = p.translation_word_id
          WHERE p.tag_id = t.id
          ORDER BY p.id
          LIMIT 1)
    )
WHERE t.source_language IS NULL OR t.target_language IS NULL;

UPDATE tags SET source_language = 'de' WHERE source_language IS NULL;
UPDATE tags SET target_language = CASE WHEN source_language = 'hu' THEN 'de' ELSE 'hu' END
 WHERE target_language IS NULL;

ALTER TABLE tags ALTER COLUMN source_language SET NOT NULL;
ALTER TABLE tags ALTER COLUMN target_language SET NOT NULL;
