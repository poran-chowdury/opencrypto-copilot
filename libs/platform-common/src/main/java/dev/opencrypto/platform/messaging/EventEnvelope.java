package dev.opencrypto.platform.messaging;

import java.time.Instant;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Wire format for every message. {@code messageId} is the outbox row id: stable across
 * re-publishes, so consumers can deduplicate with {@link InboxGuard}.
 */
public record EventEnvelope(String messageId, String type, Instant occurredAt, String correlationId, JsonNode payload) {}
