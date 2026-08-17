-- The SQLite mirror of postgresql/V9__game_track_results.sql. See that file for what `track_results` gates.
ALTER TABLE games ADD COLUMN track_results BOOLEAN NOT NULL DEFAULT 0;
