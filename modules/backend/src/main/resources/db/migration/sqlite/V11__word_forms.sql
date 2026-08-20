-- The SQLite mirror of postgresql/V11__word_forms.sql. See that file for what the table is for.
CREATE TABLE word_forms (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    lemma_word_id INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,
    form_word_id  INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,
    relation      TEXT NOT NULL,
    created_at    INTEGER NOT NULL,
    UNIQUE (lemma_word_id, form_word_id, relation)
);

CREATE INDEX idx_word_forms_lemma ON word_forms(lemma_word_id);
CREATE INDEX idx_word_forms_form  ON word_forms(form_word_id);
