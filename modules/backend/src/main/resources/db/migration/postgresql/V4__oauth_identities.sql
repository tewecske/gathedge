CREATE TABLE oauth_identities (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider   VARCHAR(32) NOT NULL,
    subject    VARCHAR(255) NOT NULL,
    email      VARCHAR(255),
    created_at BIGINT NOT NULL,
    UNIQUE (provider, subject)
);

CREATE INDEX idx_oauth_identities_user_id ON oauth_identities(user_id);

INSERT INTO oauth_identities (user_id, provider, subject, email, created_at)
SELECT id, 'google', google_subject, email, created_at FROM users WHERE google_subject IS NOT NULL;

ALTER TABLE users DROP COLUMN google_subject;
