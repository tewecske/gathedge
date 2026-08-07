-- Two tables an administrator can read, neither of which holds anything a user owns.
--
-- `login_attempts` is the history behind the in-memory rate limiter: the limiter still decides who
-- is blocked (5 failures / 15 min, per key), but it lives in a Ref and dies with the process, so
-- nothing could ever answer "how many times did this account fail, and from where". This table can.
--
-- `audit_log` is the queryable half of the `security` slf4j logger. Every administrator action
-- already emits a SecurityLog line; the same call site now also lands here so the admin UI can show
-- it. `detail` is prose for a human and must never carry a credential.
--
-- Both reference users with ON DELETE SET NULL rather than CASCADE: deleting an account must not
-- erase the record of what was done to it or by it. `audit_log.actor_email` is denormalised for the
-- same reason — it is the only trace of who acted once the actor's row is gone.

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
