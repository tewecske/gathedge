-- Single-use tokens for the "forgot password" flow, the same shape as `email_verification_tokens`:
-- see that table for why a timestamp is epoch-millis BIGINT rather than a native type. `consumed_at`
-- set means the link has already been used, mirroring how a verification token is invalidated rather
-- than deleted on redemption.
CREATE TABLE password_reset_tokens (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token       VARCHAR(64) NOT NULL UNIQUE,
    created_at  BIGINT NOT NULL,
    expires_at  BIGINT NOT NULL,
    consumed_at BIGINT
);

CREATE INDEX idx_password_reset_tokens_user_id ON password_reset_tokens(user_id);
