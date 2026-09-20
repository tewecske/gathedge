-- Turns the play's definite-article switch from a boolean into a three-way mode, and freezes the declension
-- cell each prompt of a play is asked in.

-- `include_definite_articles` had two settings and now needs three: show every article a language has, show
-- only the ones the prompt's own declension cell allows, or show none. `'all'` is what `TRUE` meant and
-- `'none'` what `FALSE` did, so the backfill below is exact -- no play changes the variant it ran under.
-- See `ArticleMode`. NOT NULL with a default, unlike the nullable columns V14 added: every play written from
-- here on names a mode, and the backfill leaves no row without one.
ALTER TABLE game_plays ADD COLUMN article_mode VARCHAR(16) NOT NULL DEFAULT 'all';

UPDATE game_plays
   SET article_mode = CASE WHEN include_definite_articles THEN 'all' ELSE 'none' END
 WHERE include_definite_articles IS NOT NULL;

ALTER TABLE game_plays DROP COLUMN include_definite_articles;

-- Which `word_forms.relation` each side of a sampled pair is asked under -- and through it, which cell of the
-- declension table decides the article (`GrammarTag.slotOf`). A word that inflects nothing, which is every
-- lemma, keeps NULL and stands in the citation cell.
--
-- Stored rather than re-derived per request for the reason the dropped `game_word_pool` gave for freezing its
-- own draw: a form carries many relations (`Lieben` has 23), `startPlay` picks one of them, and a prompt that
-- re-picked on every read could show `des Lieben` and then grade against `den Lieben`. Importing a new form
-- edge mid-play must not change the question either.
--
-- Plain VARCHAR(255) with no FK to `word_forms`: the column records what was asked, and deleting the edge it
-- names must not erase the history of a finished play -- the same reasoning `word_id`/`translation_word_id`
-- on this table already do not cascade from `words`.
ALTER TABLE game_play_words ADD COLUMN word_relation        VARCHAR(255);
ALTER TABLE game_play_words ADD COLUMN translation_relation VARCHAR(255);
