package dev.opencrypto.walletservice.ledger;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Independent check that the ledger is internally consistent. It re-derives every balance from
 * postings and verifies three invariants:
 * <ol>
 *  <li>each account's stored balance equals the sum of its postings;</li>
 *  <li>each asset sums to zero across all accounts (value is moved, never created);</li>
 *  <li>each user's HELD balance equals their open reservations.</li>
 * </ol>
 * Runs in a REPEATABLE READ transaction so it sees one consistent snapshot while swaps continue.
 * Any mismatch is stored, logged as an error and exported as a metric you can alert on.
 */
@Component
public class ReconciliationJob {
 private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

 public record Run(long id, Instant startedAt, Instant finishedAt, int accountsChecked, int balanceMismatches,
   int unbalancedAssets, int holdMismatches, String status, String details) {}

 private final JdbcClient jdbc;
 private final TransactionTemplate tx;
 private final ObjectMapper json;
 private final AtomicInteger lastMismatches = new AtomicInteger();

 public ReconciliationJob(JdbcClient jdbc, PlatformTransactionManager txManager, ObjectMapper json, MeterRegistry meters) {
  this.jdbc = jdbc;
  this.json = json;
  this.tx = new TransactionTemplate(txManager);
  this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  Gauge.builder("ledger.reconciliation.mismatches", lastMismatches, AtomicInteger::get)
   .description("Problems found by the last ledger reconciliation (should be 0)").register(meters);
 }

 @Scheduled(fixedDelayString = "${wallet.reconciliation-interval:PT5M}", initialDelayString = "PT30S")
 @SchedulerLock(name = "ledger-reconciliation", lockAtMostFor = "PT10M")
 public void scheduled() { run(); }

 public Run run() {
  return tx.execute(status -> {
   Instant started = Instant.now();
   List<Map<String, Object>> problems = new ArrayList<>();
   int accounts = jdbc.sql("SELECT count(*) FROM account").query(Integer.class).single();
   List<Map<String, Object>> balance = jdbc.sql("""
     SELECT a.id, a.owner_type, a.owner_id, a.bucket, a.asset, a.balance, COALESCE(sum(p.amount), 0) AS posted
     FROM account a LEFT JOIN posting p ON p.account_id = a.id
     GROUP BY a.id HAVING a.balance <> COALESCE(sum(p.amount), 0)""").query().listOfRows();
   balance.forEach(r -> problems.add(Map.of("check", "BALANCE_EQUALS_POSTINGS", "row", r)));
   List<Map<String, Object>> assets = jdbc.sql("SELECT asset, sum(balance) AS total FROM account GROUP BY asset HAVING sum(balance) <> 0").query().listOfRows();
   assets.forEach(r -> problems.add(Map.of("check", "ASSET_SUMS_TO_ZERO", "row", r)));
   List<Map<String, Object>> holds = jdbc.sql("""
     WITH held AS (SELECT owner_id AS user_id, asset, balance FROM account WHERE owner_type = 'USER' AND bucket = 'HELD'),
          res  AS (SELECT user_id, asset, sum(amount) AS amount FROM reservation WHERE status = 'HELD' GROUP BY user_id, asset)
     SELECT COALESCE(h.user_id, r.user_id) AS user_id, COALESCE(h.asset, r.asset) AS asset,
            COALESCE(h.balance, 0) AS held_balance, COALESCE(r.amount, 0) AS reserved
     FROM held h FULL JOIN res r ON r.user_id = h.user_id AND r.asset = h.asset
     WHERE COALESCE(h.balance, 0) <> COALESCE(r.amount, 0)""").query().listOfRows();
   holds.forEach(r -> problems.add(Map.of("check", "HELD_EQUALS_RESERVATIONS", "row", r)));

   String details;
   try { details = json.writeValueAsString(problems); } catch (Exception e) { details = "[]"; }
   String result = problems.isEmpty() ? "OK" : "MISMATCH";
   long id = jdbc.sql("""
     INSERT INTO reconciliation_run (started_at, finished_at, accounts_checked, balance_mismatches, unbalanced_assets, hold_mismatches, status, details)
     VALUES (:s, now(), :a, :b, :u, :h, :st, CAST(:d AS jsonb)) RETURNING id""")
    .param("s", Timestamp.from(started)).param("a", accounts).param("b", balance.size()).param("u", assets.size())
    .param("h", holds.size()).param("st", result).param("d", details).query(Long.class).single();
   lastMismatches.set(problems.size());
   if (problems.isEmpty()) log.info("Ledger reconciliation OK ({} accounts)", accounts);
   else log.error("Ledger reconciliation found {} problems: {}", problems.size(), details);
   return latest().orElseThrow();
  });
 }

 public Optional<Run> latest() {
  return jdbc.sql("SELECT * FROM reconciliation_run ORDER BY id DESC LIMIT 1").query((rs, i) -> new Run(rs.getLong("id"),
   rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("finished_at").toInstant(), rs.getInt("accounts_checked"),
   rs.getInt("balance_mismatches"), rs.getInt("unbalanced_assets"), rs.getInt("hold_mismatches"), rs.getString("status"), rs.getString("details"))).optional();
 }
}
