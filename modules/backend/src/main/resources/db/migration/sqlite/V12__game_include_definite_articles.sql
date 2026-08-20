-- The SQLite mirror of postgresql/V12__game_include_definite_articles.sql. See that file for what
-- `include_definite_articles` gates.
ALTER TABLE games ADD COLUMN include_definite_articles BOOLEAN NOT NULL DEFAULT 1;
