-- Lets a game's owner see who played it and how they scored (`GameService.listPlays`/`getPlayDetail`). This
-- gates the owner-facing *read* only: `game_plays`/`game_play_answers` are written unconditionally by every
-- play, tracked or not — see `GameService.startPlay`/`submitAnswer` — exactly as before this column existed.

-- `FALSE` (the default, and what every existing game keeps) is today's only behaviour: the two owner-facing
-- endpoints answer `NotTracked`. Set once, at `createGame`; there is no route to change it after creation.
ALTER TABLE games ADD COLUMN track_results BOOLEAN NOT NULL DEFAULT FALSE;
