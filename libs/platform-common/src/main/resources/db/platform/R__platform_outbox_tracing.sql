-- Carries the W3C trace context of the transaction that wrote the event, so the trace continues
-- when the event is published later by the outbox poller.
--
-- Why a *repeatable* migration: platform migrations ship inside every service, next to the
-- service's own V1, V2, ... A new platform V-number would sort before versions a service has
-- already applied, and Flyway would refuse to start ("out of order"). Additive, idempotent
-- changes (IF NOT EXISTS) are safe to run as repeatable migrations instead.
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS trace_parent VARCHAR(64);
