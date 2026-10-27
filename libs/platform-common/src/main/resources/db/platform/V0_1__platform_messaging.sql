-- Shared messaging tables, created in each service's own schema (Flyway location classpath:db/platform).

-- Transactional outbox: events are inserted in the same transaction as the business change and
-- published to RabbitMQ afterwards, so "state changed" and "event sent" can never disagree.
CREATE TABLE outbox_event (
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(64)  NOT NULL,
    aggregate_id   VARCHAR(128) NOT NULL,
    routing_key    VARCHAR(128) NOT NULL,
    event_type     VARCHAR(128) NOT NULL,
    payload        JSONB        NOT NULL,
    correlation_id VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     TEXT
);
CREATE INDEX idx_outbox_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;

-- Inbox / idempotent consumer: a message id is processed at most once per consumer, even though
-- RabbitMQ (and the outbox) deliver at-least-once.
CREATE TABLE processed_message (
    message_id   VARCHAR(64) NOT NULL,
    consumer     VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, consumer)
);
