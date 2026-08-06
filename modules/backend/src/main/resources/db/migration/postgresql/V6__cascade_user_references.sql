-- `group_pairs.created_by` (V2) and `group_invitations.invited_by` (V3) were the only two foreign
-- keys onto `users` declared without an ON DELETE action, so deleting an account that had ever
-- added a pair or sent an invitation raised a constraint violation instead of removing the row —
-- `AdminService.deleteUser` answered 500 for exactly those users.
--
-- Both now cascade: deleting a user also removes the pairs they authored and the invitations they
-- sent, including from groups shared with other members. `group_pairs.created_by_email` keeps the
-- authorship record for the pairs that remain, and neither column is read for logic anywhere.
--
-- The constraint names are Postgres' own defaults, which is what these tables were created with.

ALTER TABLE group_pairs DROP CONSTRAINT group_pairs_created_by_fkey;
ALTER TABLE group_pairs
    ADD CONSTRAINT group_pairs_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE group_invitations DROP CONSTRAINT group_invitations_invited_by_fkey;
ALTER TABLE group_invitations
    ADD CONSTRAINT group_invitations_invited_by_fkey
    FOREIGN KEY (invited_by) REFERENCES users(id) ON DELETE CASCADE;
