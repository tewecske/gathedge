-- The vocabulary feature, plus the guest accounts that let a visitor use it without signing up.
--
-- The two conventions from V1__init.sql hold here too: every timestamp is epoch millis in a BIGINT,
-- and a reference to `users` cascades unless the row records something that must outlive the
-- account. `words.created_by` and `word_translations.created_by` are the SET NULL cases — a word
-- somebody else has tagged must not vanish when its author deletes their account, and a dictionary
-- row has no author at all.

-- A guest is an ordinary account with no address and no password: it holds a real session, so every
-- route, aspect and foreign key downstream treats it like any other user, and upgrading it to a real
-- account is an UPDATE on this same row rather than a copy of everything it owns.
--
-- Both dialects treat NULLs in a UNIQUE index as distinct, which is exactly what is wanted here: any
-- number of guests coexist, while two real accounts still cannot share an address.
ALTER TABLE users ADD COLUMN is_guest BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;

-- One lexical unit. Dictionary rows and user-typed rows share this table on purpose — the whole
-- point of the feature is that typing a word finds the one already there — and `source` is what
-- tells them apart.
--
-- Two columns are NOT NULL with a sentinel rather than nullable, and both would be bugs the other
-- way round: a NULL counts as distinct in a UNIQUE index on both dialects, so a nullable `gender`
-- would let the same word in twice; and the dialects disagree about where NULLs sort, which is the
-- ordering the search results depend on.
--
-- `gender` is part of the identity key, so `der See` and `die See` are two different words. That is
-- the point of teaching gender at all.
CREATE TABLE words (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    language       VARCHAR(8)   NOT NULL,                     -- 'en' | 'de' | 'hu'
    text           VARCHAR(255) NOT NULL,                     -- display form: "Haus"
    text_norm      VARCHAR(255) NOT NULL,                     -- lowercased; the only thing search touches
    part_of_speech VARCHAR(32)  NOT NULL,                     -- 'noun' | 'verb' | 'adjective' | 'adverb' | 'other'
    gender         VARCHAR(8)   NOT NULL DEFAULT '',          -- 'der' | 'die' | 'das'; '' = not gendered
    frequency_rank INTEGER      NOT NULL DEFAULT 999999999,   -- lower is commoner; the sentinel is "unranked"
    source         VARCHAR(16)  NOT NULL,                     -- 'dictionary' | 'user'
    created_by     BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at     BIGINT NOT NULL,
    UNIQUE (language, text_norm, part_of_speech, gender)
);

-- Search is a prefix match on `text_norm` (`LIKE 'hau%'`), which is what an autocomplete needs and
-- what this index answers on both dialects. A substring index would mean pg_trgm, i.e. an extension
-- and the privileges to create it, on a schema the application may not own exclusively.
CREATE INDEX idx_words_lookup ON words(language, text_norm);
CREATE INDEX idx_words_rank   ON words(language, frequency_rank);

-- A translation is an edge between two `words` rows rather than a string, so the German side of a
-- pair carries its own gender and part of speech.
--
-- Edges are stored in BOTH directions: the importer writes the mirror row, and so does a user adding
-- one. Every read is then `WHERE source_word_id = ?` with no union, and the phase-2 game can prompt
-- either way round with no special case.
--
-- `origin` distinguishes what the dictionary asserts directly ('dictionary'), what was derived by
-- pivoting German and Hungarian on a shared English sense ('pivot' — there is no free de<->hu data
-- anywhere, so this is the only way that pair exists), and what a user added ('user').
CREATE TABLE word_translations (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_word_id BIGINT NOT NULL REFERENCES words(id) ON DELETE CASCADE,
    target_word_id BIGINT NOT NULL REFERENCES words(id) ON DELETE CASCADE,
    origin         VARCHAR(16) NOT NULL,                      -- 'dictionary' | 'pivot' | 'user'
    created_by     BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at     BIGINT NOT NULL,
    UNIQUE (source_word_id, target_word_id, created_by)
);

CREATE INDEX idx_word_translations_source ON word_translations(source_word_id, origin);
CREATE INDEX idx_word_translations_target ON word_translations(target_word_id);

-- Tags are per-account and are the whole notion of ownership in this feature: a word is in your
-- vocabulary if and only if you have tagged it. They are entities with ids rather than strings on a
-- word, because a name may contain anything and the tag bar needs to list them anyway.
CREATE TABLE tags (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       VARCHAR(64) NOT NULL,
    name_norm  VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE (user_id, name_norm)
);

CREATE TABLE word_tags (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    word_id    BIGINT NOT NULL REFERENCES words(id) ON DELETE CASCADE,
    tag_id     BIGINT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    created_at BIGINT NOT NULL,
    UNIQUE (word_id, tag_id)
);

CREATE INDEX idx_word_tags_tag ON word_tags(tag_id);

-- What carries a guest's vocabulary to another machine, in place of a login. The code *is* the
-- bearer credential, exactly like `sessions.id` and the email verification tokens: it is stored as
-- issued, shown to its owner once, and must never reach a log line. It stays usable on several
-- devices, and is revoked when the account gains an address and a password.
CREATE TABLE guest_claim_codes (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code         VARCHAR(64) NOT NULL UNIQUE,
    created_at   BIGINT NOT NULL,
    last_used_at BIGINT,
    revoked_at   BIGINT
);

CREATE INDEX idx_guest_claim_codes_user_id ON guest_claim_codes(user_id);
