-- The SQLite mirror of postgresql/V17__game_favorites.sql. See that file for what the table is for
-- and why both references cascade. As everywhere else in this schema no foreign key is actually
-- enforced, so the cascades below are exercised only by PostgresIntegrationSpec.
CREATE TABLE game_favorites (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    game_id    INTEGER NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    UNIQUE (user_id, game_id)
);

CREATE INDEX idx_game_favorites_game ON game_favorites(game_id);
