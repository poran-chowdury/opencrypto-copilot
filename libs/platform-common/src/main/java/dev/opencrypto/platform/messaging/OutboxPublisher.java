package dev.opencrypto.platform.messaging;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.tracing.Span;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import dev.opencrypto.platform.tracing.TraceContext;

/**
 * Relays outbox rows to RabbitMQ.
 *
 * Rows are claimed with {@code FOR UPDATE SKIP LOCKED}, so several instances of a service can
 * run this safely without publishing the same row concurrently. Each message waits for a
 * publisher confirm before the row is marked published. If the process dies between "broker
 * confirmed" and "row committed", the row is sent again: delivery is at-least-once, and
 * consumers deduplicate on {@code messageId}.
 *
 * The poll loop runs on its <b>own thread</b>, not on Spring's {@code @Scheduled} pool. That pool has
 * one thread by default and is shared with every other job in the service; under load a slow job
 * (swap-service's timeout recovery) held it for minutes and nothing was published at all
 * (docs/engineering/11-load-testing.md). Relaying events is too important to wait in line.
 */
public class OutboxPublisher implements SmartLifecycle {
 private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
 private static final int BATCH = 50;

 record Row(UUID id, String routingKey, String eventType, String payload, String correlationId, String traceParent, Timestamp createdAt) {}

 private final JdbcClient jdbc;
 private final RabbitTemplate rabbit;
 private final TransactionTemplate tx;
 private final ObjectMapper json;
 private final TraceContext trace;
 private final AtomicLong pending = new AtomicLong();
 private final Counter published, failures;
 private final long pollIntervalMs;
 private volatile ScheduledExecutorService poller;

 public OutboxPublisher(JdbcClient jdbc, RabbitTemplate rabbit, TransactionTemplate tx, ObjectMapper json, MeterRegistry meters, TraceContext trace,
   long pollIntervalMs) {
  this.pollIntervalMs = pollIntervalMs;
  this.trace = trace;
  this.jdbc = jdbc;
  this.rabbit = rabbit;
  this.tx = tx;
  this.json = json;
  Gauge.builder("outbox.pending", pending, AtomicLong::get).description("Unpublished outbox events").register(meters);
  this.published = Counter.builder("outbox.published").register(meters);
  this.failures = Counter.builder("outbox.publish.failures").register(meters);
 }

 @Override
 public void start() {
  poller = Executors.newSingleThreadScheduledExecutor(r -> {
   Thread t = new Thread(r, "outbox-publisher");
   t.setDaemon(true);
   return t;
  });
  // Fixed delay: the next poll starts pollIntervalMs after the previous one finished, never overlapping.
  poller.scheduleWithFixedDelay(this::publishPending, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
 }

 /** Lets an in-flight batch finish (it's one transaction either way) before the database and broker close. */
 @Override
 public void stop() {
  ScheduledExecutorService p = poller;
  if (p == null) return;
  p.shutdown();
  try {
   if (!p.awaitTermination(5, TimeUnit.SECONDS)) p.shutdownNow();
  } catch (InterruptedException e) {
   p.shutdownNow();
   Thread.currentThread().interrupt();
  }
  poller = null;
 }

 @Override
 public boolean isRunning() {
  return poller != null;
 }

 public void publishPending() {
  try {
   publishBatch();
  } catch (Throwable e) {
   // Database or broker briefly unavailable (or shutting down): rows stay unpublished and the next poll retries.
   // Throwable, not Exception: a ScheduledExecutorService silently stops repeating a task that throws.
   log.warn("Outbox poll failed, will retry: {}", e.getMessage());
  }
 }

 private void publishBatch() {
  Integer count = tx.execute(status -> {
   List<Row> rows = jdbc.sql("""
     SELECT id, routing_key, event_type, payload::text AS payload, correlation_id, trace_parent, created_at FROM outbox_event
     WHERE published_at IS NULL ORDER BY created_at LIMIT :batch FOR UPDATE SKIP LOCKED""")
    .param("batch", BATCH).query(Row.class).list();
   int sent = 0;
   for (Row r : rows) {
    try {
     send(r);
     jdbc.sql("UPDATE outbox_event SET published_at = now(), attempts = attempts + 1, last_error = NULL WHERE id = :id").param("id", r.id()).update();
     published.increment();
     sent++;
    } catch (Exception e) {
     failures.increment();
     jdbc.sql("UPDATE outbox_event SET attempts = attempts + 1, last_error = :err WHERE id = :id")
      .param("id", r.id()).param("err", String.valueOf(e.getMessage())).update();
     log.warn("Outbox publish failed for {} ({}); will retry: {}", r.id(), r.eventType(), e.getMessage());
     break; // keep ordering: stop this batch, retry on the next poll
    }
   }
   return sent;
  });
  pending.set(jdbc.sql("SELECT count(*) FROM outbox_event WHERE published_at IS NULL").query(Long.class).single());
  if (count != null && count > 0) log.debug("Published {} outbox events", count);
 }

 /** Published rows are only needed for debugging; keep a week. */
 @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
 public void purgePublished() {
  int n = jdbc.sql("DELETE FROM outbox_event WHERE published_at < :cutoff")
   .param("cutoff", Timestamp.from(Instant.now().minus(7, ChronoUnit.DAYS))).update();
  if (n > 0) log.info("Purged {} published outbox events", n);
 }

 /**
  * Publishes inside a PRODUCER span whose parent is the trace context stored with the row, so the
  * message shows up in the trace of the request that caused it, not in a trace of the poller.
  * The span's own traceparent goes into the message headers; the consumer continues from there.
  */
 private void send(Row r) throws Exception {
  JsonNode payload = json.readTree(r.payload());
  EventEnvelope envelope = new EventEnvelope(r.id().toString(), r.eventType(), r.createdAt().toInstant(), r.correlationId(), payload);
  Span span = trace.startChildOf(r.traceParent(), "publish " + r.routingKey(), Span.Kind.PRODUCER);
  if (span != null) {
   // OpenTelemetry semantic conventions for messaging
   span.tag("messaging.system", "rabbitmq").tag("messaging.operation.type", "publish")
    .tag("messaging.destination.name", Messaging.EXCHANGE).tag("messaging.rabbitmq.destination.routing_key", r.routingKey())
    .tag("messaging.message.id", envelope.messageId()).remoteServiceName("rabbitmq");
  }
  try (var scope = trace.inScope(span)) {
   publish(r, envelope, span);
  } catch (Exception e) {
   if (span != null) span.error(e);
   throw e;
  } finally {
   if (span != null) span.end();
  }
 }

 private void publish(Row r, EventEnvelope envelope, Span span) {
  rabbit.invoke(ops -> {
   ops.convertAndSend(Messaging.EXCHANGE, r.routingKey(), envelope, m -> {
    MessageProperties p = m.getMessageProperties();
    p.setMessageId(envelope.messageId());
    p.setHeader(Messaging.HEADER_EVENT_TYPE, r.eventType());
    if (r.correlationId() != null) p.setHeader(Messaging.HEADER_CORRELATION_ID, r.correlationId());
    trace.inject(span, p, MessageProperties::setHeader);
    return m;
   });
   ops.waitForConfirmsOrDie(5_000);
   return null;
  });
 }
}
