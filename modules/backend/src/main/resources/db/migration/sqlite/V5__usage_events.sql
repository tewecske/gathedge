-- The SQLite mirror of postgresql/V5__usage_events.sql. See that file for what the table is for;
-- see V1__init.sql for why nothing here enforces the foreign key.
CREATE TABLE usage_events (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at INTEGER NOT NULL,
    method     TEXT NOT NULL,
    route      TEXT NOT NULL,
    status     INTEGER NOT NULL,
    user_id    INTEGER REFERENCES users(id) ON DELETE SET NULL,
    ip         TEXT
);

CREATE INDEX idx_usage_events_created_at ON usage_events(created_at);
CREATE INDEX idx_usage_events_route_created_at ON usage_events(route, created_at);
CREATE INDEX idx_usage_events_user_id_created_at ON usage_events(user_id, created_at);
