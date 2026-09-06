-- Issue #42: optimistic locking for the entities a person edits through a form.
--
-- Each row gets a `version` counter. Every UPDATE the application makes to one of these tables bumps
-- it (`version = version + 1`); a write that reads a row, decides, and then writes it back guards the
-- write with `WHERE ... AND version = ?` and treats zero rows affected as a conflict. This closes the
-- read-modify-write window inside one request. It is not carried on the wire, so it does not catch a
-- form submitted from a stale page -- that is a later change.
--
-- `NOT NULL DEFAULT 0` so every existing row starts at 0 and no INSERT has to name the column.
--
-- `words` is left out on purpose: a word is shared by every account and has no single-entity edit endpoint. Its one
-- write path, `WordRepository.setWordGender`, already guards itself with `WHERE gender = ''`.
ALTER TABLE tags          ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE groups        ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE group_members ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE games         ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE users         ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
