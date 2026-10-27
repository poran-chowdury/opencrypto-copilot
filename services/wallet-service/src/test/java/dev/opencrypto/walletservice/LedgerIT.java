package dev.opencrypto.walletservice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import dev.opencrypto.platform.web.ApiException;
import dev.opencrypto.walletservice.ledger.LedgerService;
import dev.opencrypto.walletservice.ledger.ReconciliationJob;
import dev.opencrypto.walletservice.ledger.WalletService;
import dev.opencrypto.walletservice.pricing.AssetClient;
import dev.opencrypto.walletservice.saga.WalletMessages.*;

/**
 * Real Postgres + RabbitMQ (Testcontainers). Proves the properties unit tests can't:
 * row locking under concurrency, database-enforced invariants and idempotency races.
 */
@Testcontainers
@SpringBootTest(properties = {"spring.rabbitmq.listener.simple.auto-startup=false", "wallet.reconciliation-interval=PT1H"})
class LedgerIT {
 @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
 @Container static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

 @DynamicPropertySource
 static void props(DynamicPropertyRegistry r) {
  r.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=wallet");
  r.add("spring.datasource.username", postgres::getUsername);
  r.add("spring.datasource.password", postgres::getPassword);
  r.add("spring.rabbitmq.host", rabbit::getHost);
  r.add("spring.rabbitmq.port", rabbit::getAmqpPort);
  r.add("spring.rabbitmq.username", rabbit::getAdminUsername);
  r.add("spring.rabbitmq.password", rabbit::getAdminPassword);
 }

 @MockitoBean AssetClient assets;
 @Autowired WalletService wallet;
 @Autowired LedgerService ledger;
 @Autowired ReconciliationJob reconciliation;
 @Autowired JdbcClient jdbc;
 @Autowired org.springframework.transaction.support.TransactionTemplate tx;

 @Test
 void concurrentReservationsNeverOverspend() throws Exception {
  String user = "race-" + UUID.randomUUID();
  wallet.ensureOpened(user); // 10,000 USD
  int threads = 20;
  ExecutorService pool = Executors.newFixedThreadPool(threads);
  CountDownLatch start = new CountDownLatch(1);
  List<Future<?>> futures = new ArrayList<>();
  for (int i = 0; i < threads; i++) {
   String swapId = "swap-" + i + "-" + user;
   futures.add(pool.submit(() -> {
    start.await();
    tx.executeWithoutResult(s -> wallet.reserve(new ReserveFunds(swapId, user, "USD", new BigDecimal("1000"))));
    return null;
   }));
  }
  start.countDown();
  for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
  pool.shutdown();

  Map<String, Long> byStatus = new TreeMap<>();
  jdbc.sql("SELECT status, count(*) FROM reservation WHERE user_id = :u GROUP BY status").param("u", user)
   .query(rs -> { byStatus.put(rs.getString(1), rs.getLong(2)); });
  assertThat(byStatus).containsEntry("HELD", 10L).containsEntry("REJECTED", 10L);
  assertThat(ledger.balances(user).get("USD")).satisfies(b -> {
   assertThat(b.get("AVAILABLE")).isEqualByComparingTo("0");
   assertThat(b.get("HELD")).isEqualByComparingTo("10000");
  });
  assertThat(reconciliation.run().status()).isEqualTo("OK");
 }

 @Test
 void concurrentRetriesWithOneIdempotencyKeyCreditOnce() throws Exception {
  when(assets.asset(anyString())).thenReturn(new AssetClient.AssetInfo("USD", "US Dollar", "FIAT", 2, BigDecimal.ONE, false));
  String user = "idem-" + UUID.randomUUID();
  ExecutorService pool = Executors.newFixedThreadPool(8);
  List<Future<Boolean>> results = new ArrayList<>();
  for (int i = 0; i < 8; i++) results.add(pool.submit(() -> wallet.deposit(user, "USD", new BigDecimal("50"), "same-key-123").replayed()));
  long replays = 0;
  for (Future<Boolean> f : results) if (f.get(30, TimeUnit.SECONDS)) replays++;
  pool.shutdown();
  assertThat(replays).isEqualTo(7);
  assertThat(ledger.balances(user).get("USD").get("AVAILABLE")).isEqualByComparingTo("10050");
 }

 @Test
 void sameKeyDifferentRequestIsRejected() {
  when(assets.asset(anyString())).thenReturn(new AssetClient.AssetInfo("USD", "US Dollar", "FIAT", 2, BigDecimal.ONE, false));
  String user = "reuse-" + UUID.randomUUID();
  wallet.deposit(user, "USD", new BigDecimal("10"), "key-reuse-001");
  assertThatThrownBy(() -> wallet.deposit(user, "USD", new BigDecimal("20"), "key-reuse-001"))
   .isInstanceOf(ApiException.class).hasMessageContaining("different request");
 }

 @Test
 void swapSettlementUpdatesPositionsAndStaysBalanced() {
  String user = "settle-" + UUID.randomUUID();
  String swap = "s-" + UUID.randomUUID();
  tx.executeWithoutResult(s -> wallet.reserve(new ReserveFunds(swap, user, "USD", new BigDecimal("1000"))));
  tx.executeWithoutResult(s -> wallet.settle(new SettleSwap(swap, user, "USD", new BigDecimal("1000"), new BigDecimal("2.50"),
   "BTC", new BigDecimal("0.01246875"), BigDecimal.ONE, new BigDecimal("80000"))));
  // retrying the same command is a no-op (idempotent per swap id)
  tx.executeWithoutResult(s -> wallet.settle(new SettleSwap(swap, user, "USD", new BigDecimal("1000"), new BigDecimal("2.50"),
   "BTC", new BigDecimal("0.01246875"), BigDecimal.ONE, new BigDecimal("80000"))));
  var balances = ledger.balances(user);
  assertThat(balances.get("USD").get("AVAILABLE")).isEqualByComparingTo("9000");
  assertThat(balances.get("USD").get("HELD")).isEqualByComparingTo("0");
  assertThat(balances.get("BTC").get("AVAILABLE")).isEqualByComparingTo("0.01246875");
  BigDecimal realized = jdbc.sql("SELECT realized_pnl_usd FROM position WHERE user_id = :u AND asset = 'USD'").param("u", user).query(BigDecimal.class).single();
  assertThat(realized).isEqualByComparingTo("-2.50"); // the fee
  assertThat(reconciliation.run().status()).isEqualTo("OK");
 }

 @Test
 void releaseAfterSettlementReportsSettledInsteadOfReleasing() {
  String user = "late-" + UUID.randomUUID();
  String swap = "s-" + UUID.randomUUID();
  tx.executeWithoutResult(s -> wallet.reserve(new ReserveFunds(swap, user, "USD", new BigDecimal("100"))));
  tx.executeWithoutResult(s -> wallet.settle(new SettleSwap(swap, user, "USD", new BigDecimal("100"), new BigDecimal("0.25"),
   "ETH", new BigDecimal("0.04"), BigDecimal.ONE, new BigDecimal("2500"))));
  tx.executeWithoutResult(s -> wallet.release(new ReleaseFunds(swap, user, "TIMEOUT")));
  String lastEvent = jdbc.sql("SELECT event_type FROM outbox_event WHERE aggregate_id = :id ORDER BY created_at DESC LIMIT 1")
   .param("id", swap).query(String.class).single();
  assertThat(lastEvent).isEqualTo("SwapSettled");
  assertThat(ledger.balances(user).get("ETH").get("AVAILABLE")).isEqualByComparingTo("0.04");
 }

 @Test
 void releaseBeforeReserveLeavesTombstoneSoLateReserveIsRefused() {
  String user = "tomb-" + UUID.randomUUID();
  String swap = "s-" + UUID.randomUUID();
  wallet.ensureOpened(user);
  tx.executeWithoutResult(s -> wallet.release(new ReleaseFunds(swap, user, "TIMEOUT")));
  tx.executeWithoutResult(s -> wallet.reserve(new ReserveFunds(swap, user, "USD", new BigDecimal("100"))));
  assertThat(jdbc.sql("SELECT status FROM reservation WHERE swap_id = :id").param("id", swap).query(String.class).single()).isEqualTo("RELEASED");
  assertThat(ledger.balances(user).get("USD").get("AVAILABLE")).isEqualByComparingTo("10000");
 }

 @Test
 void databaseRejectsUnbalancedEntriesAndHistoryEdits() {
  String user = "db-" + UUID.randomUUID();
  wallet.ensureOpened(user);
  assertThatThrownBy(() -> jdbc.sql("UPDATE posting SET amount = amount * 2").update())
   .isInstanceOf(DataAccessException.class).hasStackTraceContaining("append-only");
  assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
   UUID id = UUID.randomUUID();
   jdbc.sql("INSERT INTO journal_entry (id, idempotency_key, request_hash, user_id, type, description) VALUES (:id, :k, 'x', :u, 'HACK', 'free money')")
    .param("id", id).param("k", "hack-" + id).param("u", user).update();
   jdbc.sql("INSERT INTO posting (entry_id, account_id, asset, amount) SELECT :id, id, asset, 1000 FROM account WHERE owner_id = :u AND asset = 'USD' AND bucket = 'AVAILABLE'")
    .param("id", id).param("u", user).update();
  })).isInstanceOf(DataAccessException.class).hasStackTraceContaining("not balanced");
  assertThatThrownBy(() -> jdbc.sql("UPDATE account SET balance = -1 WHERE owner_id = :u").param("u", user).update())
   .isInstanceOf(DataAccessException.class).hasStackTraceContaining("balance_non_negative");
 }
}
