-- Who made a form-of link, the same two facts `word_translations` keeps about a translation.
--
-- `origin` is 'dictionary' for a row the wiktextract import wrote and 'user' for one a reader made, by a
-- tabular import's extra column or by hand in the wordlist editor. `created_by` is that reader, and NULL
-- for a dictionary row.
--
-- The two decide who may remove a row. A dictionary row is shared data: only a global administrator may
-- remove it, and only after confirming. A reader's own row is theirs to remove.
--
-- `created_by` is ON DELETE SET NULL, as on `words` and `word_translations`: the link outlives its
-- author, and a link nobody owns any more can then be removed by an administrator only.
ALTER TABLE word_forms ADD COLUMN origin     VARCHAR(16) NOT NULL DEFAULT 'dictionary';
ALTER TABLE word_forms ADD COLUMN created_by BIGINT REFERENCES users(id) ON DELETE SET NULL;

-- The rule V27 follows: index the referencing side, so deleting an account does not scan the table.
CREATE INDEX idx_word_forms_created_by ON word_forms(created_by);

-- Rows written before this migration carry no author. A link whose form word a reader minted came from
-- that reader's tabular import, so it is theirs. Every other row stays a dictionary row: when in doubt,
-- the protected side.
UPDATE word_forms
SET origin = 'user', created_by = w.created_by
FROM words w
WHERE w.id = word_forms.form_word_id
  AND w.source = 'user'
  AND w.created_by IS NOT NULL;
