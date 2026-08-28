-- A per-account "favorite" mark on a game, for the games listing's heart toggle, its "my favorites"
-- filter, and its per-game like count / sort.
--
-- Both references cascade, the same rule V6__games.sql states: a favorite is personal data tied to
-- one account and one game, an administrative record of neither, so nothing here has to outlive the
-- account or the game it names. `user_id` cascades like `game_plays.player_user_id`; `game_id`
-- cascades like every other reference to `games(id)` in this schema.
CREATE TABLE game_favorites (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    game_id    BIGINT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    created_at BIGINT NOT NULL,
    UNIQUE (user_id, game_id)
);

-- The UNIQUE index above already answers "this account's favorites" as a leftmost prefix. This is the
-- other direction: "how many accounts favorited this game", the listing's like count and its sort.
CREATE INDEX idx_game_favorites_game ON game_favorites(game_id);
