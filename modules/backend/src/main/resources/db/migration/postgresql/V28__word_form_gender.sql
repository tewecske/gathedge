-- Gives every declined form the gender of the word it inflects, so a form can be shown and scored with its
-- own definite article.
--
-- A form is a `words` row like any other, and until now it carried the `''` "not gendered" sentinel: the
-- dictionary import wrote the gender onto the lemma only. Without a gender there is no article at all, so
-- `die Sache` had a plural that read as a bare `Sachen`. The gender is what picks the column of the
-- declension table; `word_forms.relation` picks the row (see `GrammarTag.slotOf`).
--
-- Three guards, each of which leaves a row alone rather than guessing:
--
--   * `form.gender = ''` -- a form that already states its own gender keeps it. The relation itself can name
--     one that differs from the lemma's: `Wort` [neuter] -> `Wörtlein` is `diminutive,neuter`, and
--     `Verteidiger` [masculine] -> `Verteidigerin` is `feminine`. Those are right and the lemma's is not.
--
--   * `COUNT(DISTINCT lemma.gender) = 1` -- a form shared by lemmas of different genders has no single answer.
--     `Lieben` inflects both `Lieber` [masculine] and `Liebe` [feminine]; `Rechten` both `Rechte` [feminine]
--     and `Recht` [neuter]. In every such row found in the dictionary the disagreeing edge is a plural, where
--     the German article ignores gender entirely (`den Lieben` whichever lemma it came from), so leaving the
--     column empty costs no article that could have been shown.
--
--   * the `NOT EXISTS` twin check -- `words` is UNIQUE on (language, text_norm, part_of_speech, gender), so a
--     form whose gendered identity is already taken by another row cannot take it too. Writing it would abort
--     the migration; skipping it leaves the form exactly as it is today.
--
-- Data only, and re-runnable in effect: a second run finds every row it would write already written and the
-- `form.gender = ''` guard excludes them.
WITH inherited AS (
    SELECT wf.form_word_id  AS form_id,
           MIN(lemma.gender) AS gender
      FROM word_forms wf
      JOIN words lemma ON lemma.id = wf.lemma_word_id
      JOIN words form  ON form.id  = wf.form_word_id
     WHERE lemma.gender <> ''
       AND form.gender  =  ''
       AND form.language = lemma.language
     GROUP BY wf.form_word_id
    HAVING COUNT(DISTINCT lemma.gender) = 1
)
UPDATE words form
   SET gender = inherited.gender
  FROM inherited
 WHERE form.id = inherited.form_id
   AND NOT EXISTS (
       SELECT 1
         FROM words twin
        WHERE twin.language       = form.language
          AND twin.text_norm      = form.text_norm
          AND twin.part_of_speech = form.part_of_speech
          AND twin.gender         = inherited.gender
   );
