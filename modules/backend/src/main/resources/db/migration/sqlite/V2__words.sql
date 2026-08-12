-- The SQLite mirror of postgresql/V2__words.sql. See that file for what each table is for and why
-- `gender` and `frequency_rank` carry sentinels rather than NULLs.
--
-- One statement cannot be mirrored: SQLite cannot drop a NOT NULL or alter a UNIQUE column at any
-- version, so making `users.email` nullable is a full table rebuild rather than an ALTER. It is safe
-- here only because nothing enables `PRAGMA foreign_keys` (the pragma is a no-op inside Flyway's
-- transaction anyway), so dropping and recreating `users` breaks no reference. `is_guest` is folded
-- into the rebuild instead of being a separate ADD COLUMN, since the table is being written out
-- anyway.
CREATE TABLE users_new (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    email             TEXT UNIQUE,
    password_hash     TEXT,
    is_admin          BOOLEAN NOT NULL DEFAULT 0,
    theme             TEXT NOT NULL DEFAULT 'light',
    created_at        INTEGER NOT NULL,
    email_verified_at INTEGER,
    locale            TEXT NOT NULL DEFAULT 'en',
    is_guest          BOOLEAN NOT NULL DEFAULT 0
);

INSERT INTO users_new (id, email, password_hash, is_admin, theme, created_at, email_verified_at, locale, is_guest)
SELECT id, email, password_hash, is_admin, theme, created_at, email_verified_at, locale, 0 FROM users;

DROP TABLE users;

ALTER TABLE users_new RENAME TO users;

CREATE TABLE words (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    language       TEXT NOT NULL,
    text           TEXT NOT NULL,
    text_norm      TEXT NOT NULL,
    part_of_speech TEXT NOT NULL,
    gender         TEXT NOT NULL DEFAULT '',
    frequency_rank INTEGER NOT NULL DEFAULT 999999999,
    source         TEXT NOT NULL,
    created_by     INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at     INTEGER NOT NULL,
    UNIQUE (language, text_norm, part_of_speech, gender)
);

CREATE INDEX idx_words_lookup ON words(language, text_norm);
CREATE INDEX idx_words_rank   ON words(language, frequency_rank);

CREATE TABLE word_translations (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    source_word_id INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,
    target_word_id INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,
    origin         TEXT NOT NULL,
    created_by     INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at     INTEGER NOT NULL,
    UNIQUE (source_word_id, target_word_id, created_by)
);

CREATE INDEX idx_word_translations_source ON word_translations(source_word_id, origin);
CREATE INDEX idx_word_translations_target ON word_translations(target_word_id);

CREATE TABLE tags (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    name_norm  TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE (user_id, name_norm)
);

CREATE TABLE word_tags (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    word_id    INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,
    tag_id     INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    UNIQUE (word_id, tag_id)
);

CREATE INDEX idx_word_tags_tag ON word_tags(tag_id);

CREATE TABLE guest_claim_codes (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code         TEXT NOT NULL UNIQUE,
    created_at   INTEGER NOT NULL,
    last_used_at INTEGER,
    revoked_at   INTEGER
);

CREATE INDEX idx_guest_claim_codes_user_id ON guest_claim_codes(user_id);
