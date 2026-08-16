-- One row per API request, written by `RouteSupport.usageTracking`. What most/least-used-feature
-- reporting and suspicious-activity detection are both read off. `route` is normalized (numeric path
-- segments become `{id}`) rather than the raw path, both so aggregation groups `/api/words/42` and
-- `/api/words/43` together and so a future path segment carrying a secret is never persisted here —
-- see the note on `RouteSupport.loggableUrl`.
--
-- Like `login_attempts`, this is the one table an outsider can grow at will: every request writes a
-- row, session or none, so the reaper bounds it the same way (`app.usage-event-retention-days`).
CREATE TABLE usage_events (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at BIGINT NOT NULL,
    method     VARCHAR(8) NOT NULL,
    route      VARCHAR(255) NOT NULL,
    status     INTEGER NOT NULL,
    user_id    BIGINT REFERENCES users(id) ON DELETE SET NULL,
    ip         VARCHAR(64)
);

CREATE INDEX idx_usage_events_created_at ON usage_events(created_at);
CREATE INDEX idx_usage_events_route_created_at ON usage_events(route, created_at);
CREATE INDEX idx_usage_events_user_id_created_at ON usage_events(user_id, created_at);
