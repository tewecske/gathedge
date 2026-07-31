CREATE TABLE users (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email          VARCHAR(255) NOT NULL UNIQUE,
    password_hash  VARCHAR(255),
    is_admin       BOOLEAN NOT NULL DEFAULT FALSE,
    theme          VARCHAR(20) NOT NULL DEFAULT 'light',
    google_subject VARCHAR(255) UNIQUE,
    created_at     BIGINT NOT NULL
);

CREATE TABLE sessions (
    id         VARCHAR(64) PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    revoked_at BIGINT
);

CREATE INDEX idx_sessions_user_id ON sessions(user_id);
