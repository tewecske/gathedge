CREATE TABLE todo_items (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    text       TEXT NOT NULL,
    status     TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE INDEX idx_todo_items_user_id ON todo_items(user_id);

CREATE TABLE groups (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE group_members (
    group_id INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id  INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role     TEXT NOT NULL,
    joined_at INTEGER NOT NULL,
    PRIMARY KEY (group_id, user_id)
);

CREATE INDEX idx_group_members_user_id ON group_members(user_id);

CREATE TABLE group_pairs (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id         INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    source           TEXT NOT NULL,
    target           TEXT NOT NULL,
    created_by       INTEGER NOT NULL REFERENCES users(id),
    created_by_email TEXT NOT NULL,
    created_at       INTEGER NOT NULL
);

CREATE INDEX idx_group_pairs_group_id ON group_pairs(group_id);
