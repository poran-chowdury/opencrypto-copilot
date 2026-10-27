package dev.opencrypto.platform.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent-consumer guard. Call {@link #firstDelivery} at the start of the listener's
 * transaction: it returns false for a message this consumer already handled, and the insert is
 * rolled back together with the business change if processing fails, so a retry is allowed.
 */
public class InboxGuard {
 private final JdbcClient jdbc;

 public InboxGuard(JdbcClient jdbc) { this.jdbc = jdbc; }

 @Transactional(propagation = Propagation.MANDATORY)
 public boolean firstDelivery(String messageId, String consumer) {
  return jdbc.sql("INSERT INTO processed_message (message_id, consumer) VALUES (:id, :consumer) ON CONFLICT DO NOTHING")
   .param("id", messageId).param("consumer", consumer).update() == 1;
 }
}
