package dev.opencrypto.walletservice.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import dev.opencrypto.platform.web.ApiException;
import dev.opencrypto.platform.web.DemoUser;
import dev.opencrypto.walletservice.ledger.LedgerModels.JournalEntryView;
import dev.opencrypto.walletservice.ledger.LedgerModels.PostResult;
import dev.opencrypto.walletservice.ledger.LedgerService;
import dev.opencrypto.walletservice.ledger.ReconciliationJob;
import dev.opencrypto.walletservice.ledger.WalletService;

@RestController
@RequestMapping("/api/v1")
@Validated
public class WalletController {
 public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {}
 public record DepositRequest(@NotBlank String symbol, @NotNull @Positive BigDecimal amount) {}
 public record AuditEntry(long id, Instant occurredAt, String actor, String action, String entityType, String entityId, String details, String correlationId) {}

 private final PortfolioService portfolio;
 private final WalletService wallet;
 private final LedgerService ledger;
 private final ReconciliationJob reconciliation;
 private final JdbcClient jdbc;

 public WalletController(PortfolioService portfolio, WalletService wallet, LedgerService ledger, ReconciliationJob reconciliation, JdbcClient jdbc) {
  this.portfolio = portfolio;
  this.wallet = wallet;
  this.ledger = ledger;
  this.reconciliation = reconciliation;
  this.jdbc = jdbc;
 }

 @GetMapping("/portfolio")
 public PortfolioService.Portfolio portfolio(@RequestHeader(value = DemoUser.HEADER, required = false) String user) {
  return portfolio.portfolio(DemoUser.resolve(user));
 }

 @GetMapping("/portfolio/timeline")
 public Map<String, Object> timeline(@RequestHeader(value = DemoUser.HEADER, required = false) String user,
   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
   @RequestParam(defaultValue = "300") @Min(1) @Max(5000) int limit) {
  return portfolio.timeline(DemoUser.resolve(user), from, to, limit);
 }

 @GetMapping("/wallet/balances")
 public Map<String, Object> balances(@RequestHeader(value = DemoUser.HEADER, required = false) String user) {
  String u = DemoUser.resolve(user);
  wallet.ensureOpened(u);
  return Map.of("userId", u, "balances", ledger.balances(u));
 }

 /** The user's journal entries with their double-entry postings, newest first. */
 @GetMapping("/wallet/ledger")
 public PageResponse<JournalEntryView> journal(@RequestHeader(value = DemoUser.HEADER, required = false) String user,
   @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
   @RequestParam(required = false) String type) {
  String u = DemoUser.resolve(user);
  wallet.ensureOpened(u);
  String t = type == null || type.isBlank() ? null : type.toUpperCase(Locale.ROOT);
  long total = ledger.countUserEntries(u, t);
  return new PageResponse<>(ledger.userEntries(u, t, size, page * size), page, size, total, (int) Math.ceil(total / (double) size));
 }

 @GetMapping("/wallet/audit")
 public PageResponse<AuditEntry> audit(@RequestHeader(value = DemoUser.HEADER, required = false) String user,
   @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
  String u = DemoUser.resolve(user);
  long total = jdbc.sql("SELECT count(*) FROM audit_log WHERE user_id = :u").param("u", u).query(Long.class).single();
  List<AuditEntry> items = jdbc.sql("""
    SELECT id, occurred_at, actor, action, entity_type, entity_id, details::text AS details, correlation_id
    FROM audit_log WHERE user_id = :u ORDER BY id DESC LIMIT :l OFFSET :o""")
   .param("u", u).param("l", size).param("o", page * size)
   .query((rs, i) -> new AuditEntry(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4), rs.getString(5),
    rs.getString(6), rs.getString(7), rs.getString(8))).list();
  return new PageResponse<>(items, page, size, total, (int) Math.ceil(total / (double) size));
 }

 /**
  * Simulated deposit. The Idempotency-Key header is mandatory: a retried request (e.g. after a
  * timeout) returns the original entry with 200 instead of crediting twice.
  */
 @PostMapping("/wallet/deposits")
 public ResponseEntity<JournalEntryView> deposit(@RequestHeader(value = DemoUser.HEADER, required = false) String user,
   @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody DepositRequest r) {
  if (!idempotencyKey.matches("[A-Za-z0-9_-]{8,64}"))
   throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be 8-64 characters of letters, digits, '_' or '-'");
  PostResult result = wallet.deposit(DemoUser.resolve(user), r.symbol().toUpperCase(Locale.ROOT), r.amount(), idempotencyKey);
  return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
   .header("Idempotent-Replayed", String.valueOf(result.replayed())).body(result.entry());
 }

 @PostMapping("/wallet/reset")
 public Map<String, Object> reset(@RequestHeader(value = DemoUser.HEADER, required = false) String user) {
  String u = DemoUser.resolve(user);
  return Map.of("userId", u, "entries", wallet.reset(u), "balances", ledger.balances(u));
 }

 @GetMapping("/wallet/reconciliation")
 public Object latestReconciliation() {
  return reconciliation.latest().<Object>map(r -> r).orElse(Map.of("status", "NOT_RUN_YET"));
 }

 @PostMapping("/wallet/reconciliation/run")
 public ReconciliationJob.Run runReconciliation() { return reconciliation.run(); }
}
