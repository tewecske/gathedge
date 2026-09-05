-- The SQLite mirror of postgresql/V24__tag_languages_not_null.sql. See that file for the backfill
-- rules.
--
-- SQLite cannot add NOT NULL to an existing column, so after the backfill the `tags` table is
-- rebuilt -- the pattern V2's note describes. No foreign key is enforced on SQLite, so the rebuild
-- only has to carry the columns, the UNIQUE constraint and the one index (idx_tags_group).

UPDATE tags SET
    source_language = COALESCE(
        source_language,
        (SELECT w.language FROM word_tag_pairs p JOIN words w ON w.id = p.word_id
          WHERE p.tag_id = tags.id ORDER BY p.id LIMIT 1),
        (SELECT w.language FROM word_tags m JOIN words w ON w.id = m.word_id
          WHERE m.tag_id = tags.id ORDER BY m.id LIMIT 1)
    ),
    target_language = COALESCE(
        target_language,
        (SELECT w.language FROM word_tag_pairs p JOIN words w ON w.id = p.translation_word_id
          WHERE p.tag_id = tags.id ORDER BY p.id LIMIT 1)
    )
WHERE source_language IS NULL OR target_language IS NULL;

UPDATE tags SET source_language = 'de' WHERE source_language IS NULL;
UPDATE tags SET target_language = CASE WHEN source_language = 'hu' THEN 'de' ELSE 'hu' END
 WHERE target_language IS NULL;

CREATE TABLE tags_new (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    name_norm       TEXT NOT NULL,
    created_at      INTEGER NOT NULL,
    group_id        INTEGER REFERENCES groups(id) ON DELETE SET NULL,
    source_language TEXT NOT NULL,
    target_language TEXT NOT NULL,
    UNIQUE (user_id, name_norm)
);

INSERT INTO tags_new (id, user_id, name, name_norm, created_at, group_id, source_language, target_language)
SELECT id, user_id, name, name_norm, created_at, group_id, source_language, target_language FROM tags;

DROP TABLE tags;
ALTER TABLE tags_new RENAME TO tags;

CREATE INDEX idx_tags_group ON tags(group_id);
