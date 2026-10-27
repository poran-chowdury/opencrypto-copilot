package dev.opencrypto.walletservice.ledger;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import dev.opencrypto.platform.messaging.OutboxWriter;
import dev.opencrypto.platform.web.ApiException;
import dev.opencrypto.walletservice.ledger.LedgerModels.*;
import dev.opencrypto.walletservice.pricing.AssetClient;
import dev.opencrypto.walletservice.saga.WalletMessages.*;
import static dev.opencrypto.walletservice.saga.WalletMessages.*;

/**
 * Wallet use cases on top of the ledger: opening balance, deposits, reset, and the three saga
 * steps for swaps (reserve, settle, release). Every saga step is idempotent per swap id, because
 * RabbitMQ may deliver a command more than once.
 */
@Service
public class WalletService {
 public enum ReservationStatus { HELD, CAPTURED, RELEASED, REJECTED }

 private final LedgerService ledger;
 private final PositionService positions;
 private final AuditLog audit;
 private final OutboxWriter outbox;
 private final AssetClient assets;
 private final JdbcClient jdbc;
 private final BigDecimal openingUsd;
 private final BigDecimal maxDepositUsd;
 private final TransactionTemplate tx;

 public WalletService(LedgerService ledger, PositionService positions, AuditLog audit, OutboxWriter outbox, AssetClient assets, JdbcClient jdbc, TransactionTemplate tx,
   @Value("${wallet.opening-balance-usd:10000}") BigDecimal openingUsd, @Value("${wallet.max-deposit-usd:1000000}") BigDecimal maxDepositUsd) {
  this.ledger = ledger;
  this.positions = positions;
  this.audit = audit;
  this.outbox = outbox;
  this.assets = assets;
  this.jdbc = jdbc;
  this.openingUsd = openingUsd;
  this.maxDepositUsd = maxDepositUsd;
  this.tx = tx;
 }

 /** New users get simulated cash on first contact. Safe under concurrency: the key makes it happen once. */
 @Transactional
 public void ensureOpened(String userId) {
  if (ledger.hasAccounts(userId)) return;
  open(userId, "opening:" + userId);
 }

 private void open(String userId, String key) {
  PostResult r = ledger.post(key, userId, "OPENING_BALANCE", "opening", "Simulated opening balance", List.of(
   new Leg(AccountRef.system(AccountRef.FAUCET, "USD"), openingUsd.negate()),
   new Leg(AccountRef.available(userId, "USD"), openingUsd)));
  if (!r.replayed()) positions.acquire(userId, "USD", openingUsd, openingUsd);
 }

 /**
  * Not @Transactional on purpose: the price lookup is a network call and must not hold a database
  * connection open. Only the ledger writes run inside the transaction.
  */
 public PostResult deposit(String userId, String symbol, BigDecimal amount, String idempotencyKey) {
  AssetClient.AssetInfo asset;
  try { asset = assets.asset(symbol); }
  catch (Exception e) { throw ApiException.unavailable("PRICES_UNAVAILABLE", "Cannot value the deposit right now; try again shortly"); }
  if (asset == null || asset.priceUsd() == null) throw ApiException.badRequest("UNSUPPORTED_ASSET", "Unsupported asset: " + symbol);
  if (asset.stale()) throw ApiException.unavailable("PRICES_STALE", "Prices are stale; deposits are paused until market data is fresh");
  if (amount.signum() <= 0) throw ApiException.badRequest("INVALID_AMOUNT", "amount must be positive");
  if (amount.stripTrailingZeros().scale() > asset.decimals())
   throw ApiException.badRequest("INVALID_AMOUNT", asset.symbol() + " supports at most " + asset.decimals() + " decimal places");
  BigDecimal valueUsd = amount.multiply(asset.priceUsd());
  if (valueUsd.compareTo(maxDepositUsd) > 0) throw ApiException.badRequest("DEPOSIT_TOO_LARGE", "Maximum deposit value is " + maxDepositUsd.toPlainString() + " USD");
  return tx.execute(status -> postDeposit(userId, asset, amount, valueUsd, idempotencyKey));
 }

 private PostResult postDeposit(String userId, AssetClient.AssetInfo asset, BigDecimal amount, BigDecimal valueUsd, String idempotencyKey) {
  ensureOpened(userId);
  PostResult r = ledger.post("deposit:" + userId + ":" + idempotencyKey, userId, "DEPOSIT", "faucet",
   "Demo deposit of %s %s".formatted(amount.stripTrailingZeros().toPlainString(), asset.symbol()), List.of(
    new Leg(AccountRef.system(AccountRef.FAUCET, asset.symbol()), amount.negate()),
    new Leg(AccountRef.available(userId, asset.symbol()), amount)));
  if (!r.replayed()) {
   positions.acquire(userId, asset.symbol(), amount, valueUsd);
   outbox.append("wallet", userId, EVT_DEPOSIT_COMPLETED, "DepositCompleted",
    new DepositCompleted(userId, r.entry().id().toString(), asset.symbol(), amount, valueUsd));
  }
  return r;
 }

 /** Close every balance back to the faucet and re-open. Nothing is deleted from the ledger. */
 @Transactional
 public List<JournalEntryView> reset(String userId) {
  ensureOpened(userId);
  Map<String, Map<String, BigDecimal>> balances = ledger.balances(userId);
  for (var e : balances.entrySet())
   if (e.getValue().getOrDefault("HELD", BigDecimal.ZERO).signum() != 0)
    throw ApiException.conflict("SWAP_IN_PROGRESS", "A swap is still in progress for " + e.getKey() + "; try again when it finishes");
  List<Leg> legs = new ArrayList<>();
  balances.forEach((asset, buckets) -> {
   BigDecimal available = buckets.getOrDefault("AVAILABLE", BigDecimal.ZERO);
   if (available.signum() != 0) {
    legs.add(new Leg(AccountRef.available(userId, asset), available.negate()));
    legs.add(new Leg(AccountRef.system(AccountRef.FAUCET, asset), available));
   }
  });
  List<JournalEntryView> entries = new ArrayList<>();
  String run = UUID.randomUUID().toString();
  if (!legs.isEmpty()) entries.add(ledger.post("reset:" + run, userId, "RESET", "reset", "Close all simulated balances", legs).entry());
  positions.clear(userId);
  open(userId, "opening:" + userId + ":" + run);
  JournalEntryView opening = ledger.findByKey("opening:" + userId + ":" + run).orElseThrow();
  entries.add(opening);
  audit.record(userId, "WALLET_RESET", "user", userId, userId, Map.of("closedLegs", legs.size()));
  outbox.append("wallet", userId, EVT_WALLET_RESET, "WalletReset", new WalletReset(userId, opening.id().toString()));
  return entries;
 }

 // ---- saga steps ------------------------------------------------------------------------

 @Transactional
 public void reserve(ReserveFunds c) {
  Optional<ReservationStatus> existing = reservationStatus(c.swapId());
  if (existing.isPresent()) { replyToRepeatedReserve(c, existing.get()); return; }
  ensureOpened(c.userId());
  BigDecimal available = lockAvailable(c.userId(), c.asset());
  if (available.compareTo(c.amount()) < 0) {
   insertReservation(c.swapId(), c.userId(), c.asset(), c.amount(), ReservationStatus.REJECTED, "INSUFFICIENT_FUNDS");
   outbox.append("swap", c.swapId(), EVT_RESERVATION_REJECTED, "ReservationRejected", new ReservationRejected(c.swapId(), c.userId(), "INSUFFICIENT_FUNDS",
    "Available %s %s, required %s".formatted(available.stripTrailingZeros().toPlainString(), c.asset(), c.amount().stripTrailingZeros().toPlainString())));
   return;
  }
  ledger.post("reserve:" + c.swapId(), c.userId(), "SWAP_RESERVE", c.swapId(), "Hold %s %s for swap".formatted(c.amount().stripTrailingZeros().toPlainString(), c.asset()), List.of(
   new Leg(AccountRef.available(c.userId(), c.asset()), c.amount().negate()),
   new Leg(AccountRef.held(c.userId(), c.asset()), c.amount())));
  insertReservation(c.swapId(), c.userId(), c.asset(), c.amount(), ReservationStatus.HELD, null);
  outbox.append("swap", c.swapId(), EVT_FUNDS_RESERVED, "FundsReserved", new FundsReserved(c.swapId(), c.userId(), c.asset(), c.amount()));
 }

 @Transactional
 public void settle(SettleSwap c) {
  ReservationStatus status = lockReservation(c.swapId()).orElse(null);
  if (status == ReservationStatus.CAPTURED) { replaySettled(c.swapId(), c.userId()); return; }
  if (status != ReservationStatus.HELD) {
   outbox.append("swap", c.swapId(), EVT_SETTLEMENT_REJECTED, "SettlementRejected",
    new SettlementRejected(c.swapId(), c.userId(), status == null ? "NO_RESERVATION" : "RESERVATION_" + status));
   return;
  }
  PostResult r = ledger.post("settle:" + c.swapId(), c.userId(), "SWAP", c.swapId(),
   "Swap %s %s -> %s %s".formatted(c.inputAmount().stripTrailingZeros().toPlainString(), c.fromAsset(), c.outputAmount().stripTrailingZeros().toPlainString(), c.toAsset()), List.of(
    new Leg(AccountRef.held(c.userId(), c.fromAsset()), c.inputAmount().negate()),
    new Leg(AccountRef.system(AccountRef.FEES, c.fromAsset()), c.fee()),
    new Leg(AccountRef.system(AccountRef.EXCHANGE, c.fromAsset()), c.inputAmount().subtract(c.fee())),
    new Leg(AccountRef.system(AccountRef.EXCHANGE, c.toAsset()), c.outputAmount().negate()),
    new Leg(AccountRef.available(c.userId(), c.toAsset()), c.outputAmount())).stream().filter(l -> l.amount().signum() != 0).toList());
  BigDecimal receivedUsd = c.outputAmount().multiply(c.toPriceUsd());
  positions.dispose(c.userId(), c.fromAsset(), c.inputAmount(), receivedUsd);
  positions.acquire(c.userId(), c.toAsset(), c.outputAmount(), receivedUsd);
  updateReservation(c.swapId(), ReservationStatus.CAPTURED, null);
  outbox.append("swap", c.swapId(), EVT_SWAP_SETTLED, "SwapSettled",
   new SwapSettled(c.swapId(), c.userId(), r.entry().id().toString(), c.toAsset(), c.outputAmount()));
 }

 /**
  * Release held funds. If the swap was already settled, the truth is "completed", so we answer
  * with SwapSettled again; if nothing was ever reserved, a RELEASED tombstone makes sure a late
  * reserve command for this swap is refused.
  */
 @Transactional
 public void release(ReleaseFunds c) {
  ReservationStatus status = lockReservation(c.swapId()).orElse(null);
  if (status == ReservationStatus.CAPTURED) { replaySettled(c.swapId(), c.userId()); return; }
  if (status == null) {
   insertReservation(c.swapId(), c.userId(), "NONE", BigDecimal.ZERO, ReservationStatus.RELEASED, "Released before reservation: " + c.reason());
  } else if (status == ReservationStatus.HELD) {
   Map<String, Object> r = jdbc.sql("SELECT asset, amount FROM reservation WHERE swap_id = :id").param("id", c.swapId()).query().singleRow();
   String asset = (String) r.get("asset");
   BigDecimal amount = (BigDecimal) r.get("amount");
   ledger.post("release:" + c.swapId(), c.userId(), "SWAP_RELEASE", c.swapId(), "Release held %s %s (%s)".formatted(amount.stripTrailingZeros().toPlainString(), asset, c.reason()), List.of(
    new Leg(AccountRef.held(c.userId(), asset), amount.negate()),
    new Leg(AccountRef.available(c.userId(), asset), amount)));
   updateReservation(c.swapId(), ReservationStatus.RELEASED, c.reason());
  }
  outbox.append("swap", c.swapId(), EVT_FUNDS_RELEASED, "FundsReleased", new FundsReleased(c.swapId(), c.userId(), c.reason()));
 }

 private void replyToRepeatedReserve(ReserveFunds c, ReservationStatus status) {
  switch (status) {
   case HELD -> outbox.append("swap", c.swapId(), EVT_FUNDS_RESERVED, "FundsReserved", new FundsReserved(c.swapId(), c.userId(), c.asset(), c.amount()));
   case CAPTURED -> replaySettled(c.swapId(), c.userId());
   case RELEASED -> outbox.append("swap", c.swapId(), EVT_RESERVATION_REJECTED, "ReservationRejected",
    new ReservationRejected(c.swapId(), c.userId(), "RESERVATION_CANCELLED", "The swap was cancelled before funds were reserved"));
   case REJECTED -> outbox.append("swap", c.swapId(), EVT_RESERVATION_REJECTED, "ReservationRejected",
    new ReservationRejected(c.swapId(), c.userId(), "INSUFFICIENT_FUNDS", "Insufficient funds"));
  }
 }

 private void replaySettled(String swapId, String userId) {
  Map<String, Object> e = jdbc.sql("""
    SELECT je.id, p.asset, p.amount FROM journal_entry je JOIN posting p ON p.entry_id = je.id JOIN account a ON a.id = p.account_id
    WHERE je.idempotency_key = :k AND a.owner_type = 'USER' AND a.bucket = 'AVAILABLE' AND p.amount > 0""")
   .param("k", "settle:" + swapId).query().singleRow();
  outbox.append("swap", swapId, EVT_SWAP_SETTLED, "SwapSettled",
   new SwapSettled(swapId, userId, e.get("id").toString(), (String) e.get("asset"), (BigDecimal) e.get("amount")));
 }

 private BigDecimal lockAvailable(String userId, String asset) {
  return jdbc.sql("SELECT balance FROM account WHERE owner_type = 'USER' AND owner_id = :u AND bucket = 'AVAILABLE' AND asset = :a FOR UPDATE")
   .param("u", userId).param("a", asset).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
 }

 private Optional<ReservationStatus> reservationStatus(String swapId) {
  return jdbc.sql("SELECT status FROM reservation WHERE swap_id = :id").param("id", swapId).query(String.class).optional().map(ReservationStatus::valueOf);
 }

 private Optional<ReservationStatus> lockReservation(String swapId) {
  return jdbc.sql("SELECT status FROM reservation WHERE swap_id = :id FOR UPDATE").param("id", swapId).query(String.class).optional().map(ReservationStatus::valueOf);
 }

 private void insertReservation(String swapId, String userId, String asset, BigDecimal amount, ReservationStatus status, String reason) {
  jdbc.sql("INSERT INTO reservation (swap_id, user_id, asset, amount, status, reason) VALUES (:id, :u, :a, :amt, :s, :r)")
   .param("id", swapId).param("u", userId).param("a", asset).param("amt", amount).param("s", status.name()).param("r", reason).update();
  audit.record("swap-saga", "RESERVATION_" + status, "reservation", swapId, userId, Map.of("asset", asset, "amount", amount.toPlainString()));
 }

 private void updateReservation(String swapId, ReservationStatus status, String reason) {
  jdbc.sql("UPDATE reservation SET status = :s, reason = COALESCE(:r, reason), updated_at = now() WHERE swap_id = :id")
   .param("s", status.name()).param("r", reason).param("id", swapId).update();
  audit.record("swap-saga", "RESERVATION_" + status, "reservation", swapId, null, Map.of("reason", Objects.toString(reason, "")));
 }
}
