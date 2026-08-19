-- Lets an account share its game progress/history with another account of its choosing — not a parent/child
-- role, just two accounts: a "sharer" (whose plays become visible) and a "viewer" (who can read them). Both
-- tables cascade from `users` the same way `guest_claim_codes` and `games` do: this is personal data that has
-- no reason to outlive either account.

-- One row per sharer with an active code, mirroring `guest_claim_codes`: minting is idempotent (the same code
-- comes back on every call until revoked), and redeeming does not consume it — several viewers may redeem the
-- same code, each producing their own `progress_shares` row below.
CREATE TABLE progress_share_codes (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code         VARCHAR(64) NOT NULL UNIQUE,
    created_at   BIGINT NOT NULL,
    last_used_at BIGINT,
    revoked_at   BIGINT
);

CREATE INDEX idx_progress_share_codes_user_id ON progress_share_codes(user_id);

-- One row per (sharer, viewer) grant. `sharer_user_id` is whose plays become readable; `viewer_user_id` is who
-- may read them, subject to each game's own `trackResults` flag — the same rule a game's owner is already
-- bound by (`GameService.listPlays`/`getPlayDetail`).
CREATE TABLE progress_shares (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sharer_user_id  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    viewer_user_id  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at      BIGINT NOT NULL,
    UNIQUE (sharer_user_id, viewer_user_id)
);

CREATE INDEX idx_progress_shares_sharer ON progress_shares(sharer_user_id);
CREATE INDEX idx_progress_shares_viewer ON progress_shares(viewer_user_id);
