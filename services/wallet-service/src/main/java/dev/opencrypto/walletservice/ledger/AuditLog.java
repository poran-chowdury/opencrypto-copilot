package dev.opencrypto.walletservice.ledger;

import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import dev.opencrypto.platform.web.CorrelationId;

/** Append-only audit trail, written in the same transaction as the change it describes. */
@Component
public class AuditLog {
 private final JdbcClient jdbc;
 private final ObjectMapper json;

 public AuditLog(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

 @Transactional(propagation = Propagation.MANDATORY)
 public void record(String actor, String action, String entityType, String entityId, String userId, Map<String, ?> details) {
  String body;
  try { body = json.writeValueAsString(details); } catch (Exception e) { body = "{}"; }
  jdbc.sql("""
    INSERT INTO audit_log (actor, action, entity_type, entity_id, user_id, details, correlation_id)
    VALUES (:actor, :action, :type, :id, :user, CAST(:details AS jsonb), :cid)""")
   .param("actor", actor).param("action", action).param("type", entityType).param("id", entityId)
   .param("user", userId).param("details", body).param("cid", CorrelationId.current())
   .update();
 }
}
