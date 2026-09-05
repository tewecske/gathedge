-- The note a reader wrote beside a word in their own list.
--
-- `levél (növény)` does not name a word called `levél (növény)`; it names `levél` and says which sense of it this row
-- means. The note is the reader's, not the word's, so it cannot live on `words` -- that table is shared by everybody,
-- and one account's disambiguation is not a fact about the word. `word_tags` is the row that already means "this word
-- is in my vocabulary", scoped to one (word, tag) pair and cascading with the tag, which makes it the only place the
-- note belongs.
--
-- Nullable, because almost no row has one. Written by the tabular import; the editor shows it and may clear it.
ALTER TABLE word_tags ADD COLUMN comment VARCHAR(255);
