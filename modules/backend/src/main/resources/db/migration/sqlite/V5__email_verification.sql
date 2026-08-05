-- ADD COLUMN is one of the few ALTER TABLE forms SQLite supports, so unlike V4 this migration
-- needs no table rebuild.
ALTER TABLE users ADD COLUMN email_verified_at INTEGER;

-- Every account that predates verification is treated as verified: they were created when
-- proving the address was not part of signing up, so leaving them NULL would lock them out
-- the moment REQUIRE_EMAIL_VERIFICATION is switched on.
UPDATE users SET email_verified_at = created_at;

CREATE TABLE email_verification_tokens (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token       TEXT NOT NULL UNIQUE,
    created_at  INTEGER NOT NULL,
    expires_at  INTEGER NOT NULL,
    consumed_at INTEGER
);

CREATE INDEX idx_email_verification_tokens_user_id ON email_verification_tokens(user_id);
