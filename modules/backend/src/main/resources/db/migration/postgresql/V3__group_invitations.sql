CREATE TABLE group_invitations (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id    BIGINT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    email       VARCHAR(255) NOT NULL,
    role        VARCHAR(20) NOT NULL,
    token       VARCHAR(64) NOT NULL UNIQUE,
    invited_by  BIGINT NOT NULL REFERENCES users(id),
    created_at  BIGINT NOT NULL,
    expires_at  BIGINT NOT NULL,
    accepted_at BIGINT
);

CREATE INDEX idx_group_invitations_group_id ON group_invitations(group_id);
