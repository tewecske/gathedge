ALTER TABLE users ADD COLUMN email_verified_at BIGINT;

-- Every account that predates verification is treated as verified: they were created when
-- proving the address was not part of signing up, so leaving them NULL would lock them out
-- the moment REQUIRE_EMAIL_VERIFICATION is switched on.
UPDATE users SET email_verified_at = created_at;

CREATE TABLE email_verification_tokens (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token       VARCHAR(64) NOT NULL UNIQUE,
    created_at  BIGINT NOT NULL,
    expires_at  BIGINT NOT NULL,
    consumed_at BIGINT
);

CREATE INDEX idx_email_verification_tokens_user_id ON email_verification_tokens(user_id);
