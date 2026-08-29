-- The SQLite mirror of postgresql/V18__game_play_mode.sql. See that file for why the mode belongs on the
-- play and not the game, and why a constant default keeps this a plain `ADD COLUMN` here.
ALTER TABLE game_plays ADD COLUMN mode TEXT NOT NULL DEFAULT 'typing';
