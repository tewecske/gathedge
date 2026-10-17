-- A pair marked in a wordlist is a translation. Before the fix in `WordService.createPair`, adding or editing a
-- wordlist row marked the pair in `word_tag_pairs` but wrote no `word_translations` edge. This adds the missing edges.
--
-- The rule is the one the app now applies: a pair of words has one edge per direction, whoever wrote it. A direction
-- that already has an edge is left alone; a missing one is added. This also mends a pair recorded one way only.
--
-- The edge is the reader's own (`origin = 'user'`). Its author is the owner of the wordlist that marked the pair
-- first, and it takes that mark's time. A self-pair names no translation and is skipped.
WITH firsts AS (
    SELECT DISTINCT ON (LEAST(p.word_id, p.translation_word_id), GREATEST(p.word_id, p.translation_word_id))
           LEAST(p.word_id, p.translation_word_id)    AS lo,
           GREATEST(p.word_id, p.translation_word_id) AS hi,
           t.user_id,
           p.created_at
    FROM word_tag_pairs p
    JOIN tags t ON t.id = p.tag_id
    WHERE p.word_id <> p.translation_word_id
    ORDER BY LEAST(p.word_id, p.translation_word_id), GREATEST(p.word_id, p.translation_word_id),
             p.created_at, p.id
),
directions AS (
    SELECT lo AS source_word_id, hi AS target_word_id, user_id, created_at FROM firsts
    UNION ALL
    SELECT hi, lo, user_id, created_at FROM firsts
)
INSERT INTO word_translations (source_word_id, target_word_id, origin, created_by, created_at)
SELECT d.source_word_id, d.target_word_id, 'user', d.user_id, d.created_at
FROM directions d
WHERE NOT EXISTS (
    SELECT 1
    FROM word_translations e
    WHERE e.source_word_id = d.source_word_id
      AND e.target_word_id = d.target_word_id
);
