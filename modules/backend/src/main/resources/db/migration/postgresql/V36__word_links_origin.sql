-- Who made a word link, the two facts `word_forms` keeps about a form-of link (V30).
--
-- `origin` is 'dictionary' for a link the wiktextract import wrote, and 'user' for one a reader made on the word page
-- or in the wordlist editor. `created_by` is that reader, and NULL for a dictionary link.
--
-- The two decide who may remove a link. A dictionary link is shared data: only a global administrator may remove it,
-- and only after confirming. A reader's own link is theirs to remove.
--
-- `created_by` is ON DELETE SET NULL, as on `word_forms`: the link outlives its author, and a link nobody owns any more
-- can then be removed by an administrator only. Every row before this migration came from the import.
ALTER TABLE word_links ADD COLUMN origin     VARCHAR(16) NOT NULL DEFAULT 'dictionary';
ALTER TABLE word_links ADD COLUMN created_by BIGINT REFERENCES users(id) ON DELETE SET NULL;

-- The rule V27 follows: index the referencing side, so deleting an account does not scan the table.
CREATE INDEX idx_word_links_created_by ON word_links(created_by);
