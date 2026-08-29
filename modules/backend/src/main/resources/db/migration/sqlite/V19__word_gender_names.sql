-- The SQLite mirror of postgresql/V19__word_gender_names.sql. No column width to widen — SQLite's TEXT affinity
-- ignores the declared VARCHAR length — so this is the three UPDATEs alone.
UPDATE words SET gender = 'masculine' WHERE gender = 'der';
UPDATE words SET gender = 'feminine'  WHERE gender = 'die';
UPDATE words SET gender = 'neuter'    WHERE gender = 'das';
