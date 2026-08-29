-- Renames what `words.gender` stores from the German article (`der`/`die`/`das`) to the grammatical gender itself
-- (`masculine`/`feminine`/`neuter`), so a language with different articles for the same genders — Spanish `el`/`la` —
-- can share the column. See `LanguageProfile`, which is now the only place an article literal may appear.
--
-- Widened first: `masculine` is nine characters, past the old VARCHAR(8). The mapping is injective and leaves the `''`
-- ("not gendered") sentinel alone, so the `UNIQUE (language, text_norm, part_of_speech, gender)` index from
-- V2__words.sql keeps meaning exactly what it did.
ALTER TABLE words ALTER COLUMN gender TYPE VARCHAR(16);

UPDATE words SET gender = 'masculine' WHERE gender = 'der';
UPDATE words SET gender = 'feminine'  WHERE gender = 'die';
UPDATE words SET gender = 'neuter'    WHERE gender = 'das';
