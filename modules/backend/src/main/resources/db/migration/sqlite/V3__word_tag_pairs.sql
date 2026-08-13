-- The SQLite mirror of postgresql/V3__word_tag_pairs.sql. See that file for what the table is for,
-- why it is a join table rather than a column on `word_tags`, and why the rows are bidirectional.
--
-- Nothing here needs a table rebuild: this is a plain CREATE, not an ALTER, so the constraints SQLite
-- cannot change at any version do not arise. As everywhere else in this schema no foreign key is
-- actually enforced (nothing enables `PRAGMA foreign_keys`), so the cascades below are exercised only
-- by PostgresIntegrationSpec.
CREATE TABLE word_tag_pairs (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    word_id             INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,
    tag_id              INTEGER NOT NULL REFERENCES tags(id)  ON DELETE CASCADE,
    translation_word_id INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,
    created_at          INTEGER NOT NULL,
    UNIQUE (word_id, tag_id, translation_word_id)
);

CREATE INDEX idx_word_tag_pairs_tag ON word_tag_pairs(tag_id);
