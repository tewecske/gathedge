-- Words linked to a word without being one of its forms: a noun's counterpart of another gender (Künstler and
-- Künstlerin) and a diminutive or augmentative (Haus and Häuschen). Each is a word in its own right, so it must not be
-- a `word_forms` row: that marks it a form and hides it from the "main words only" listing.
--
-- `kind` names what `linked_word_id` is to `word_id`: 'feminine', 'masculine', 'neuter', 'diminutive',
-- 'augmentative', 'diminutive-of', 'augmentative-of' (see `WordLinkKind`). Every link is stored in both directions,
-- the way `word_translations` is. Both FKs cascade: a link means nothing once either word is gone.
CREATE TABLE word_links (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    word_id        BIGINT NOT NULL REFERENCES words(id) ON DELETE CASCADE,
    linked_word_id BIGINT NOT NULL REFERENCES words(id) ON DELETE CASCADE,
    kind           VARCHAR(32) NOT NULL,
    created_at     BIGINT NOT NULL,
    UNIQUE (word_id, linked_word_id, kind),
    CHECK (word_id <> linked_word_id)
);

CREATE INDEX idx_word_links_linked ON word_links(linked_word_id);

-- An earlier import stored these links as forms. They move here by the rule `tools/WordLinks` applies to a new import,
-- and the two must agree: a relation with `diminutive` or `augmentative` among its tags is that link, on any word; a
-- noun's relation that is one gender alone (`rare` aside) is a counterpart. A reader's own rows stay where they are.
CREATE TEMPORARY TABLE moved_links AS
SELECT wf.id,
       wf.lemma_word_id,
       wf.form_word_id,
       wf.created_at,
       CASE
           WHEN ',' || wf.relation || ',' LIKE '%,diminutive,%' THEN 'diminutive'
           WHEN ',' || wf.relation || ',' LIKE '%,augmentative,%' THEN 'augmentative'
           WHEN wf.relation IN ('feminine', 'feminine,rare') THEN 'feminine'
           WHEN wf.relation IN ('masculine', 'masculine,rare') THEN 'masculine'
           WHEN wf.relation IN ('neuter', 'neuter,rare') THEN 'neuter'
       END AS kind,
       lemma.gender AS lemma_gender
FROM word_forms wf
JOIN words lemma ON lemma.id = wf.lemma_word_id
WHERE wf.origin = 'dictionary'
  AND wf.lemma_word_id <> wf.form_word_id
  AND (
      ',' || wf.relation || ',' LIKE '%,diminutive,%'
      OR ',' || wf.relation || ',' LIKE '%,augmentative,%'
      OR (lemma.part_of_speech = 'noun'
          AND wf.relation IN ('feminine', 'feminine,rare', 'masculine', 'masculine,rare', 'neuter', 'neuter,rare'))
  );

INSERT INTO word_links (word_id, linked_word_id, kind, created_at)
SELECT lemma_word_id, form_word_id, kind, created_at FROM moved_links
UNION
SELECT form_word_id,
       lemma_word_id,
       CASE
           WHEN kind = 'diminutive' THEN 'diminutive-of'
           WHEN kind = 'augmentative' THEN 'augmentative-of'
           WHEN lemma_gender IN ('masculine', 'feminine', 'neuter') THEN lemma_gender
           WHEN kind = 'feminine' THEN 'masculine'
           ELSE 'feminine'
       END,
       created_at
FROM moved_links
ON CONFLICT DO NOTHING;

DELETE FROM word_forms WHERE id IN (SELECT id FROM moved_links);

-- A word stays a form only while some *other* word names it as one.
UPDATE words SET is_form = FALSE
WHERE is_form
  AND id IN (SELECT form_word_id FROM moved_links)
  AND NOT EXISTS (
      SELECT 1 FROM word_forms f WHERE f.form_word_id = words.id AND f.lemma_word_id <> f.form_word_id
  );

DROP TABLE moved_links;
