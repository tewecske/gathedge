-- The SQLite mirror of postgresql/V16__game_drop_track_results.sql. See that file for why the opt-in
-- `track_results` flag is gone and every game's plays are now readable by its owner.
--
-- `DROP COLUMN` on a plain unconstrained column needs SQLite 3.35+, which this project's bundled
-- `sqlite-jdbc` (3.53.2.0) clears — the same reasoning V14 already relied on.
ALTER TABLE games DROP COLUMN track_results;
