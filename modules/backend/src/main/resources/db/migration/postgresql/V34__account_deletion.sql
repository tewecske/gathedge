-- A reader deletes their own account by turning its row into a tombstone, not by deleting it.
--
-- The row stays because other readers use what it owns: wordlists, games and groups. A hard delete would cascade those
-- away (`tags.user_id` and `games.owner_user_id` are ON DELETE CASCADE). The tombstone keeps the id and nothing else:
-- no address, no username, no name, no password, and every table of the person's own (sessions, identities, tokens,
-- plays, streaks, shares, usage) emptied. What is left identifies nobody.
--
-- `deleted_at` says the row is a tombstone. An administrator's delete (`AdminService.deleteUser`) stays a hard delete.
ALTER TABLE users ADD COLUMN deleted_at BIGINT;
