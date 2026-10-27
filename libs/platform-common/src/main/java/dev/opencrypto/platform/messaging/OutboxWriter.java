package dev.opencrypto.platform.messaging;

import java.util.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import dev.opencrypto.platform.tracing.TraceContext;
import dev.opencrypto.platform.web.CorrelationId;

/**
 * Records an event in the outbox table. Must run inside the caller's transaction
 * ({@link Propagation#MANDATORY}): the event commits or rolls back together with the state change.
 */
public class OutboxWriter {
 private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("messaging.outbound");
 private final JdbcClient jdbc;
 private final ObjectMapper json;
 private final TraceContext trace;

 public OutboxWriter(JdbcClient jdbc, ObjectMapper json, TraceContext trace) { this.jdbc = jdbc; this.json = json; this.trace = trace; }

 @Transactional(propagation = Propagation.MANDATORY)
 public UUID append(String aggregateType, String aggregateId, String routingKey, String eventType, Object payload) {
  UUID id = UUID.randomUUID();
  String body;
  try { body = json.writeValueAsString(payload); }
  catch (JsonProcessingException e) { throw new IllegalArgumentException("Event payload is not serialisable", e); }
  jdbc.sql("""
    INSERT INTO outbox_event (id, aggregate_type, aggregate_id, routing_key, event_type, payload, correlation_id, trace_parent)
    VALUES (:id, :aggregateType, :aggregateId, :routingKey, :eventType, CAST(:payload AS jsonb), :correlationId, :traceParent)""")
   .param("id", id).param("aggregateType", aggregateType).param("aggregateId", aggregateId)
   .param("routingKey", routingKey).param("eventType", eventType).param("payload", body)
   .param("correlationId", CorrelationId.current())
   // the trace context of *this* transaction: the publisher continues the trace from here
   .param("traceParent", trace.currentTraceParent())
   .update();
  log.info("Queued {} ({}) for {} {}", eventType, routingKey, aggregateType, aggregateId);
  return id;
 }
}
