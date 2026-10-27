package dev.opencrypto.swapservice.domain;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import dev.opencrypto.platform.web.CorrelationId;
import dev.opencrypto.swapservice.domain.SwapModels.*;

@Repository
public class SwapRepository {
 private final JdbcClient jdbc;

 public SwapRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

 public void insertQuote(Quote q, int feeRateBps) {
  jdbc.sql("""
    INSERT INTO quote (id, user_id, from_symbol, to_symbol, input_amount, fee_amount, fee_rate_bps, rate, estimated_output,
      minimum_received, slippage_bps, from_price_usd, to_price_usd, created_at, expires_at)
    VALUES (:id, :user, :from, :to, :input, :fee, :feeBps, :rate, :est, :min, :slip, :fp, :tp, :created, :expires)""")
   .param("id", q.quoteId()).param("user", q.userId()).param("from", q.fromSymbol()).param("to", q.toSymbol())
   .param("input", q.inputAmount()).param("fee", q.fee()).param("feeBps", feeRateBps).param("rate", q.rate())
   .param("est", q.estimatedOutput()).param("min", q.minimumReceived()).param("slip", q.slippageBps())
   .param("fp", q.fromPriceUsd()).param("tp", q.toPriceUsd())
   .param("created", Timestamp.from(q.createdAt())).param("expires", Timestamp.from(q.expiresAt()))
   .update();
 }

 /** Locks the quote row so two confirmations of the same quote serialise. */
 public Optional<Map<String, Object>> lockQuote(UUID id) {
  return jdbc.sql("SELECT * FROM quote WHERE id = :id FOR UPDATE").param("id", id).query().listOfRows().stream().findFirst();
 }

 public void insertOrder(UUID id, String userId, UUID quoteId, String key, Map<String, Object> q) {
  jdbc.sql("""
    INSERT INTO swap_order (id, user_id, quote_id, idempotency_key, from_symbol, to_symbol, input_amount, fee_amount,
      estimated_output, minimum_received, state)
    VALUES (:id, :user, :quote, :key, :from, :to, :input, :fee, :est, :min, 'CREATED')""")
   .param("id", id).param("user", userId).param("quote", quoteId).param("key", key)
   .param("from", q.get("from_symbol")).param("to", q.get("to_symbol")).param("input", q.get("input_amount"))
   .param("fee", q.get("fee_amount")).param("est", q.get("estimated_output")).param("min", q.get("minimum_received"))
   .update();
  history(id, null, State.CREATED, "User confirmed quote " + quoteId);
 }

 public Optional<SwapOrder> findByIdempotencyKey(String userId, String key) {
  return jdbc.sql("SELECT * FROM swap_order WHERE user_id = :u AND idempotency_key = :k").param("u", userId).param("k", key)
   .query(SwapRepository::map).optional().map(this::withHistory);
 }

 public boolean quoteAlreadyUsed(UUID quoteId) {
  return jdbc.sql("SELECT EXISTS (SELECT 1 FROM swap_order WHERE quote_id = :q)").param("q", quoteId).query(Boolean.class).single();
 }

 public Optional<SwapOrder> find(String userId, UUID id) {
  return jdbc.sql("SELECT * FROM swap_order WHERE id = :id AND user_id = :u").param("id", id).param("u", userId)
   .query(SwapRepository::map).optional().map(this::withHistory);
 }

 public Optional<SwapOrder> get(UUID id) {
  return jdbc.sql("SELECT * FROM swap_order WHERE id = :id").param("id", id).query(SwapRepository::map).optional();
 }

 public Optional<SwapOrder> lock(UUID id) {
  return jdbc.sql("SELECT * FROM swap_order WHERE id = :id FOR UPDATE").param("id", id).query(SwapRepository::map).optional();
 }

 public List<SwapOrder> page(String userId, String state, int limit, int offset) {
  return jdbc.sql("SELECT * FROM swap_order WHERE user_id = :u AND (CAST(:s AS text) IS NULL OR state = :s) ORDER BY created_at DESC LIMIT :l OFFSET :o")
   .param("u", userId).param("s", state).param("l", limit).param("o", offset).query(SwapRepository::map).list();
 }

 public long count(String userId, String state) {
  return jdbc.sql("SELECT count(*) FROM swap_order WHERE user_id = :u AND (CAST(:s AS text) IS NULL OR state = :s)")
   .param("u", userId).param("s", state).query(Long.class).single();
 }

 /** Orders stuck in a non-terminal state: never asked to release yet, or asked long ago (re-send). */
 public List<UUID> stuck(Duration openFor, Duration resendAfter) {
  return jdbc.sql("""
    SELECT id FROM swap_order WHERE state IN ('CREATED', 'PENDING')
      AND ((release_requested_at IS NULL AND updated_at < :open) OR release_requested_at < :resend)
    ORDER BY updated_at LIMIT 100""")
   .param("open", Timestamp.from(Instant.now().minus(openFor))).param("resend", Timestamp.from(Instant.now().minus(resendAfter)))
   .query((rs, i) -> rs.getObject("id", UUID.class)).list();
 }

 public void moveTo(SwapOrder o, State next, String note) {
  jdbc.sql("""
    UPDATE swap_order SET state = :s, updated_at = now(), completed_at = CASE WHEN :terminal THEN now() ELSE completed_at END
    WHERE id = :id""").param("s", next.name()).param("terminal", next.terminal()).param("id", o.orderId()).update();
  history(o.orderId(), o.state(), next, note);
 }

 public void setExecution(UUID id, BigDecimal output, BigDecimal rate, BigDecimal fromPrice, BigDecimal toPrice) {
  jdbc.sql("UPDATE swap_order SET output_amount = :o, execution_rate = :r, exec_from_price_usd = :f, exec_to_price_usd = :t WHERE id = :id")
   .param("o", output).param("r", rate).param("f", fromPrice).param("t", toPrice).param("id", id).update();
 }

 public void setSettled(UUID id, BigDecimal output, String ledgerEntryId) {
  jdbc.sql("UPDATE swap_order SET output_amount = :o, ledger_entry_id = :l, failure_reason = NULL WHERE id = :id")
   .param("o", output).param("l", ledgerEntryId).param("id", id).update();
 }

 public void setFailure(UUID id, String reason) {
  jdbc.sql("UPDATE swap_order SET failure_reason = :r WHERE id = :id").param("r", reason).param("id", id).update();
 }

 public void requestRelease(UUID id, String reason) {
  jdbc.sql("UPDATE swap_order SET pending_failure_reason = COALESCE(pending_failure_reason, :r), release_requested_at = now(), updated_at = now() WHERE id = :id")
   .param("r", reason).param("id", id).update();
 }

 public void history(UUID id, State from, State to, String note) {
  jdbc.sql("INSERT INTO swap_state_history (swap_id, from_state, to_state, note, correlation_id) VALUES (:id, :f, :t, :n, :c)")
   .param("id", id).param("f", from == null ? null : from.name()).param("t", to.name()).param("n", note).param("c", CorrelationId.current())
   .update();
 }

 public int purgeExpiredQuotes() {
  return jdbc.sql("DELETE FROM quote q WHERE expires_at < now() - INTERVAL '1 hour' AND NOT EXISTS (SELECT 1 FROM swap_order o WHERE o.quote_id = q.id)").update();
 }

 public SwapOrder withHistory(SwapOrder o) {
  return o.withHistory(jdbc.sql("SELECT from_state, to_state, note, occurred_at FROM swap_state_history WHERE swap_id = :id ORDER BY id")
   .param("id", o.orderId())
   .query((rs, i) -> new StateChange(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant())).list());
 }

 static SwapOrder map(ResultSet rs, int row) throws SQLException {
  return new SwapOrder(rs.getObject("id", UUID.class), rs.getString("user_id"), rs.getObject("quote_id", UUID.class), rs.getString("idempotency_key"),
   rs.getString("from_symbol"), rs.getString("to_symbol"), strip(rs.getBigDecimal("input_amount")), strip(rs.getBigDecimal("fee_amount")),
   strip(rs.getBigDecimal("estimated_output")), strip(rs.getBigDecimal("minimum_received")), strip(rs.getBigDecimal("output_amount")),
   strip(rs.getBigDecimal("execution_rate")), strip(rs.getBigDecimal("exec_from_price_usd")), strip(rs.getBigDecimal("exec_to_price_usd")),
   State.valueOf(rs.getString("state")), rs.getString("failure_reason"), rs.getString("pending_failure_reason"), rs.getString("ledger_entry_id"),
   instant(rs.getTimestamp("release_requested_at")), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
   instant(rs.getTimestamp("completed_at")), List.of());
 }

 private static Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }
 private static BigDecimal strip(BigDecimal v) { return v == null ? null : v.stripTrailingZeros(); }
}
