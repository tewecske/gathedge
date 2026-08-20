-- Inflected and declined forms of a word, as wiktextract's own `forms` array states them: a plural, a
-- past tense, a genitive, one cell of a German declension table or a Hungarian case-suffix table. Each
-- form is a word in its own right -- independently searchable, exactly like a gendered variant already
-- is -- linked back to the word it inflects by this table.
--
-- `relation` is not a closed enum: German alone crosses case x number x definiteness, and Hungarian's
-- case system is larger still, so the tag vocabulary wiktextract emits cannot be enumerated ahead of
-- time. It is the entry's own `forms[].tags`, canonicalised by WiktextractParser.ParsedForm --
-- lower-cased, deduplicated, sorted, comma-joined -- the same free-form-string convention `origin`
-- already follows on `word_translations`. Two decodes of the same tag set always produce the same
-- string, which is what keeps a re-import idempotent without an ON CONFLICT clause.
--
-- Both FKs cascade: a form-of relation is meaningless once either the lemma or the form itself is
-- gone, the same rule `word_translations` follows for its own two `words` references.
CREATE TABLE word_forms (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lemma_word_id BIGINT NOT NULL REFERENCES words(id) ON DELETE CASCADE,
    form_word_id  BIGINT NOT NULL REFERENCES words(id) ON DELETE CASCADE,
    relation      VARCHAR(255) NOT NULL,                    -- e.g. 'plural', 'dative,definite,plural'
    created_at    BIGINT NOT NULL,
    UNIQUE (lemma_word_id, form_word_id, relation)
);

CREATE INDEX idx_word_forms_lemma ON word_forms(lemma_word_id);
CREATE INDEX idx_word_forms_form  ON word_forms(form_word_id);
