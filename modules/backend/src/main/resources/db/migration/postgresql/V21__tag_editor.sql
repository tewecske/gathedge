-- The unified tag create/edit screen: import provenance on the join tables, and the tag's own
-- language pair.
--
-- `word_tags.imported` marks a membership that a bulk import wrote (as opposed to a hand-added
-- word); it is scoped to the (word, tag) row, so the same word imported into one tag and typed
-- into another carries the flag only where it was imported. `word_tag_pairs.exact` marks a pair
-- the import matched exactly -- both the word and its dictionary translation were in the uploaded
-- text. The editor's three filters (exact / non-exact / unmatched) are derived from these two
-- columns alone: exact = a pair with exact = TRUE; non-exact = an imported word with a pair but no
-- exact pair; unmatched = an imported word with no pair. Both default FALSE, so every existing row
-- reads as "added by hand", which is what it was.
--
-- Neither column is authoritative about anything the app enforces -- they are history, not state.
-- They are written by `WordRepository.tagWord` / `linkPair` (via the `imported` / `exact`
-- parameters) and copied as-is by nothing: `copyTag` deliberately resets `imported` to FALSE on
-- the copy, since a copy was not imported.
ALTER TABLE word_tags ADD COLUMN imported BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE word_tag_pairs ADD COLUMN exact BOOLEAN NOT NULL DEFAULT FALSE;

-- The tag's language pair, decided once and then locked -- the same rule the old create page had
-- in browser state, now on the row so the editor and a later import agree on which side of a
-- bidirectional `word_tag_pairs` is the "source". Nullable because a freshly minted empty tag has
-- no pair yet; `WordRepository.setTagLanguages` fills them the first time a row is added and never
-- again. `''`-style sentinels are not used here: absent is absent.
ALTER TABLE tags ADD COLUMN source_language VARCHAR(8);
ALTER TABLE tags ADD COLUMN target_language VARCHAR(8);
