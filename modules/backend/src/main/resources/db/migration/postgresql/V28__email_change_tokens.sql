-- Single-use tokens for confirming a change of address, the same shape as `email_verification_tokens` and
-- `password_reset_tokens`. `new_email` is the address the change moves to; the account's own `email` column is
-- untouched until the token is redeemed, so a link nobody clicks changes nothing.
CREATE TABLE email_change_tokens (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    new_email   VARCHAR(255) NOT NULL,
    token       VARCHAR(64) NOT NULL UNIQUE,
    created_at  BIGINT NOT NULL,
    expires_at  BIGINT NOT NULL,
    consumed_at BIGINT
);

CREATE INDEX idx_email_change_tokens_user_id ON email_change_tokens(user_id);
