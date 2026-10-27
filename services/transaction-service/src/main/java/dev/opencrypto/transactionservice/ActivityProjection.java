package dev.opencrypto.transactionservice;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import dev.opencrypto.platform.messaging.EventEnvelope;
import dev.opencrypto.platform.messaging.InboxGuard;
import dev.opencrypto.platform.messaging.Messaging;

/**
 * Projects swap and wallet events into the {@code activity} table (a read model).
 *
 * Events can arrive late or out of order, so each upsert only wins if it is newer than what is
 * stored ({@code WHERE activity.updated_at <= EXCLUDED.updated_at}); an older "PENDING" can never
 * overwrite a newer "COMPLETED".
 */
@Component
public class ActivityProjection {
 private static final Logger log = LoggerFactory.getLogger(ActivityProjection.class);
 static final String QUEUE = "txn.activity";

 @Configuration
 static class Topology {
  @Bean
  Declarables activityQueue() {
   return Messaging.queueWithDeadLetter(QUEUE, "swap.evt.state-changed", "wallet.evt.deposit-completed", "wallet.evt.wallet-reset");
  }
 }

 private final JdbcClient jdbc;
 private final InboxGuard inbox;
 private final TransactionTemplate tx;
 private final ObjectMapper json;

 public ActivityProjection(JdbcClient jdbc, InboxGuard inbox, TransactionTemplate tx, ObjectMapper json) {
  this.jdbc = jdbc;
  this.inbox = inbox;
  this.tx = tx;
  this.json = json;
 }

 @RabbitListener(queues = QUEUE)
 public void on(EventEnvelope e) {
  tx.executeWithoutResult(s -> {
   if (!inbox.firstDelivery(e.messageId(), "activity")) return;
   JsonNode p = e.payload();
   switch (e.type()) {
    case "SwapStateChanged" -> upsert(p.path("userId").asText(), "SWAP", p.path("swapId").asText(), p.path("state").asText(),
     "Swap %s %s → %s".formatted(plain(p.path("inputAmount")), p.path("fromSymbol").asText(), p.path("toSymbol").asText()),
     p, instant(p.path("createdAt"), e.occurredAt()), instant(p.path("changedAt"), e.occurredAt()));
    case "DepositCompleted" -> upsert(p.path("userId").asText(), "DEPOSIT", p.path("entryId").asText(), "COMPLETED",
     "Deposit %s %s".formatted(plain(p.path("amount")), p.path("asset").asText()), p, e.occurredAt(), e.occurredAt());
    case "WalletReset" -> upsert(p.path("userId").asText(), "RESET", p.path("entryId").asText(), "COMPLETED",
     "Wallet reset to the opening balance", p, e.occurredAt(), e.occurredAt());
    default -> log.warn("Ignoring unknown event {}", e.type());
   }
  });
 }

 private void upsert(String userId, String type, String referenceId, String status, String title, JsonNode details, Instant createdAt, Instant updatedAt) {
  jdbc.sql("""
    INSERT INTO activity (id, user_id, type, reference_id, status, title, details, created_at, updated_at)
    VALUES (:id, :user, :type, :ref, :status, :title, CAST(:details AS jsonb), :created, :updated)
    ON CONFLICT (type, reference_id) DO UPDATE SET status = EXCLUDED.status, title = EXCLUDED.title,
      details = EXCLUDED.details, updated_at = EXCLUDED.updated_at
    WHERE activity.updated_at <= EXCLUDED.updated_at""")
   .param("id", UUID.randomUUID()).param("user", userId).param("type", type).param("ref", referenceId).param("status", status)
   .param("title", title).param("details", details.toString())
   .param("created", Timestamp.from(createdAt)).param("updated", Timestamp.from(updatedAt))
   .update();
 }

 private static Instant instant(JsonNode n, Instant fallback) {
  if (n == null || n.isMissingNode() || n.isNull()) return fallback;
  try { return n.isNumber() ? Instant.ofEpochMilli((long) (n.asDouble() * 1000)) : Instant.parse(n.asText()); } catch (Exception ex) { return fallback; }
 }

 private static String plain(JsonNode n) {
  return n == null || n.isMissingNode() || n.isNull() ? "?" : new BigDecimal(n.asText()).stripTrailingZeros().toPlainString();
 }
}
