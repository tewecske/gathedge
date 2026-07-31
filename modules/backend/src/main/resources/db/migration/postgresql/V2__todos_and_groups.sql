CREATE TABLE todo_items (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    text       VARCHAR(2000) NOT NULL,
    status     VARCHAR(20) NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE INDEX idx_todo_items_user_id ON todo_items(user_id);

CREATE TABLE groups (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE TABLE group_members (
    group_id BIGINT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role     VARCHAR(20) NOT NULL,
    joined_at BIGINT NOT NULL,
    PRIMARY KEY (group_id, user_id)
);

CREATE INDEX idx_group_members_user_id ON group_members(user_id);

CREATE TABLE group_pairs (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id         BIGINT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    source           VARCHAR(2000) NOT NULL,
    target           VARCHAR(2000) NOT NULL,
    created_by       BIGINT NOT NULL REFERENCES users(id),
    created_by_email VARCHAR(255) NOT NULL,
    created_at       BIGINT NOT NULL
);

CREATE INDEX idx_group_pairs_group_id ON group_pairs(group_id);
