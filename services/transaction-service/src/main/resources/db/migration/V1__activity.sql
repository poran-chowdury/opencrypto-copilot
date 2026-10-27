-- transaction-service: the user's activity feed, a read model built from events published by
-- swap-service and wallet-service. It owns no money; it can always be rebuilt by replaying events.
CREATE TABLE activity (
    id           UUID PRIMARY KEY,
    user_id      VARCHAR(64) NOT NULL,
    type         VARCHAR(16) NOT NULL CHECK (type IN ('SWAP', 'DEPOSIT', 'RESET')),
    reference_id VARCHAR(64) NOT NULL,
    status       VARCHAR(16) NOT NULL,
    title        TEXT        NOT NULL,
    details      JSONB       NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    UNIQUE (type, reference_id)
);
CREATE INDEX idx_activity_user_time ON activity (user_id, created_at DESC);
CREATE INDEX idx_activity_user_type ON activity (user_id, type, status);
