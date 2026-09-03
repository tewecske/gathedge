-- The SQLite mirror of postgresql/V21__tag_editor.sql. See that file for what each column is for.
-- `ADD COLUMN` needs no table rebuild, same as every other SQLite migration in this tree.
ALTER TABLE word_tags ADD COLUMN imported BOOLEAN NOT NULL DEFAULT 0;
ALTER TABLE word_tag_pairs ADD COLUMN exact BOOLEAN NOT NULL DEFAULT 0;

ALTER TABLE tags ADD COLUMN source_language TEXT;
ALTER TABLE tags ADD COLUMN target_language TEXT;
