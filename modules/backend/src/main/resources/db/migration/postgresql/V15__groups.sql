-- Shareable tag groups: classroom-style collaboration on top of the existing tag/word model.
--
-- A group is public in name and tag list, same as a tag itself, but its roster is private: only a
-- member sees who else is in it, and only an admin sees the invite code. Membership widens who may
-- *edit a tag's content* (WordService.requireEditableTag) once that tag is attached to the group; it
-- never changes who owns the tag. Renaming/deleting a tag stays owner-only regardless of its group.
--
-- `created_by` is SET NULL, not CASCADE: unlike a tag (one owner) a group is a shared resource with a
-- roster of its own, so it must outlive whichever account happened to create it — the same reasoning
-- `words.created_by` follows for a row shared by everybody.
CREATE TABLE groups (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         VARCHAR(64) NOT NULL,
    name_norm    VARCHAR(64) NOT NULL,
    invite_code  VARCHAR(64) NOT NULL UNIQUE,
    created_by   BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at   BIGINT NOT NULL
);

CREATE INDEX idx_groups_name_norm ON groups(name_norm);

-- One row per (group, account). `role` is 'admin' or 'member'; joining by invite code always inserts
-- 'member' — admins are promoted afterward, never minted directly by a code, and a group must always
-- keep at least one admin (enforced in GroupService, not here). `user_id` cascades: a deleted account
-- cannot remain on a roster. Known accepted gap: if that account was a group's last admin, the group
-- is left with zero admins — account deletion goes through AdminService, outside GroupService's own
-- last-admin guard, and this migration does not attempt to close that gap.
CREATE TABLE group_members (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id   BIGINT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id    BIGINT NOT NULL REFERENCES users(id)  ON DELETE CASCADE,
    role       VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE (group_id, user_id)
);

CREATE INDEX idx_group_members_group ON group_members(group_id);
CREATE INDEX idx_group_members_user  ON group_members(user_id);

-- A tag belongs to at most one group. SET NULL, not CASCADE: deleting a group detaches its tags (they
-- keep every word/pair and revert to owner-only edit rights) rather than destroying somebody's
-- vocabulary.
ALTER TABLE tags ADD COLUMN group_id BIGINT REFERENCES groups(id) ON DELETE SET NULL;

CREATE INDEX idx_tags_group ON tags(group_id);
