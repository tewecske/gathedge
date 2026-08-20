-- Whether a game's German nouns show their definite article ("der"/"die"/"das") in the prompt, the accepted
-- answer, and the results table — see `GameService`'s `Word.displayText` call sites. Purely a display/scoring
-- choice: it never changes which word is eligible or how it is matched, only whether the article is part of
-- the text compared against and shown.
--
-- `TRUE` (the default, and what every existing game keeps) is today's only behaviour: the article was always
-- included. Set once, at `createGame`; there is no route to change it after creation, the same rule
-- `track_results` follows.
ALTER TABLE games ADD COLUMN include_definite_articles BOOLEAN NOT NULL DEFAULT TRUE;
