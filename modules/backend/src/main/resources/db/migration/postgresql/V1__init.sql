-- The skeleton's whole schema, as one baseline.
--
-- Two conventions hold across every table here, and both exist to keep this file and its sqlite/
-- twin schema-identical — which is what lets the SQLite-backed test suite say anything about the
-- Postgres one:
--
--   * every timestamp is epoch millis in a BIGINT (INTEGER on SQLite), never a native timestamp
--     type, because that is the one type the two dialects genuinely disagree about;
--   * a foreign key onto `users` cascades, *except* the two on `login_attempts` and `audit_log`,
--     which are ON DELETE SET NULL. Deleting an account must not erase the record of what was done
--     to it or by it — `audit_log.actor_email` is a denormalised snapshot for the same reason,
--     rather than a join that would go blank.
--
-- Note that nothing enables `PRAGMA foreign_keys` on SQLite, so none of these constraints is
-- enforced there. Referential integrity is exercised only by `PostgresIntegrationSpec`
-- (RUN_POSTGRES_TESTS=1), which is where a regression test for any of it belongs.

CREATE TABLE users (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email             VARCHAR(255) NOT NULL UNIQUE,
    password_hash     VARCHAR(255),
    is_admin          BOOLEAN NOT NULL DEFAULT FALSE,
    theme             VARCHAR(20) NOT NULL DEFAULT 'light',
    created_at        BIGINT NOT NULL,
    email_verified_at BIGINT,
    -- ISO 639-1, matching shared's `Locale.languageCode`. The URL prefix (/en, /hu) is what decides
    -- the language a page renders in; this column is for the two things a URL cannot reach —
    -- transactional email, composed server-side with no browser in the room, and seeding the
    -- redirect for a first, prefix-less visit from a new browser.
    locale            VARCHAR(10) NOT NULL DEFAULT 'en'
);

CREATE TABLE sessions (
    id         VARCHAR(64) PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    revoked_at BIGINT
);

CREATE INDEX idx_sessions_user_id ON sessions(user_id);

-- External identities. `(provider, subject)` is the only thing that may decide which account a
-- social sign-in enters, which is why it is unique here and why `email` is display metadata only.
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

CREATE TABLE email_verification_tokens (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token       VARCHAR(64) NOT NULL UNIQUE,
    created_at  BIGINT NOT NULL,
    expires_at  BIGINT NOT NULL,
    consumed_at BIGINT
);

CREATE INDEX idx_email_verification_tokens_user_id ON email_verification_tokens(user_id);

-- The history behind the in-memory rate limiter: the limiter still decides who is blocked
-- (5 failures / 15 min, per key), but it lives in a `Ref` and dies with the process, so nothing
-- else could answer "how many times did this account fail, and from where".
CREATE TABLE login_attempts (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email      VARCHAR(255) NOT NULL,
    user_id    BIGINT REFERENCES users(id) ON DELETE SET NULL,
    ip         VARCHAR(64),
    outcome    VARCHAR(32) NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE INDEX idx_login_attempts_email ON login_attempts(email, created_at);
CREATE INDEX idx_login_attempts_user_id ON login_attempts(user_id);
CREATE INDEX idx_login_attempts_created_at ON login_attempts(created_at);

-- The queryable half of the `security` slf4j logger: every administrator action emits a
-- `SecurityLog` line and lands here from the same call site, so the log and the admin screen
-- cannot describe different sets of events. `detail` is prose for a human and must never carry a
-- credential.
CREATE TABLE audit_log (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at   BIGINT NOT NULL,
    actor_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    actor_email   VARCHAR(255),
    action        VARCHAR(64) NOT NULL,
    target_type   VARCHAR(32),
    target_id     VARCHAR(64),
    detail        VARCHAR(1000),
    ip            VARCHAR(64)
);

CREATE INDEX idx_audit_log_occurred_at ON audit_log(occurred_at);
CREATE INDEX idx_audit_log_actor_user_id ON audit_log(actor_user_id);
CREATE INDEX idx_audit_log_target ON audit_log(target_type, target_id);
