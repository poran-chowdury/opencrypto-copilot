package dev.opencrypto.swapservice.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import dev.opencrypto.platform.messaging.OutboxWriter;
import dev.opencrypto.platform.web.ApiException;
import dev.opencrypto.swapservice.domain.SwapModels.*;
import dev.opencrypto.swapservice.pricing.MarketClient;
import dev.opencrypto.swapservice.saga.SwapMessages.*;
import static dev.opencrypto.swapservice.saga.SwapMessages.*;

/**
 * Quotes and the swap saga (orchestrated by this service, executed by wallet-service):
 *
 * <pre>
 *  POST /swaps ──► CREATED ──reserve──► wallet ──FundsReserved──► PENDING ──settle──► wallet ──SwapSettled──► COMPLETED
 *                     │                      └─ReservationRejected─► FAILED              └─SettlementRejected─► FAILED
 *                     └─(price moved / market down / timeout) ──release──► wallet ──FundsReleased──► FAILED
 * </pre>
 *
 * The wallet is the source of truth for money. We never mark a swap FAILED while its funds might
 * still be held: we ask the wallet to release and fail only when it confirms. If the wallet
 * answers "already settled", the swap is COMPLETED — even if we had timed out.
 */
@Service
public class SwapService {
 private static final Logger log = LoggerFactory.getLogger(SwapService.class);

 private final SwapRepository repo;
 private final MarketClient market;
 private final OutboxWriter outbox;
 private final TransactionTemplate tx;
 private final MeterRegistry meters;
 private final int feeBps;
 private final Duration quoteTtl;

 public SwapService(SwapRepository repo, MarketClient market, OutboxWriter outbox, TransactionTemplate tx, MeterRegistry meters,
   @Value("${swap.fee-bps:25}") int feeBps, @Value("${swap.quote-ttl:PT30S}") Duration quoteTtl) {
  this.repo = repo;
  this.market = market;
  this.outbox = outbox;
  this.tx = tx;
  this.meters = meters;
  this.feeBps = feeBps;
  this.quoteTtl = quoteTtl;
 }

 // ---- quotes ---------------------------------------------------------------------------

 public Quote quote(String userId, String from, String to, BigDecimal amount, int slippageBps) {
  if (from.equals(to)) throw ApiException.badRequest("SAME_ASSET", "fromSymbol and toSymbol must differ");
  if (slippageBps < 1 || slippageBps > 500) throw ApiException.badRequest("INVALID_SLIPPAGE", "slippageBps must be between 1 (0.01%) and 500 (5%)");
  Map<String, Integer> decimals = catalogue();
  if (!decimals.containsKey(from) || !decimals.containsKey(to)) throw ApiException.badRequest("UNSUPPORTED_ASSET", "Unsupported asset in pair " + from + "/" + to);
  Map<String, MarketClient.Price> p = livePrices(List.of(from, to));

  BigDecimal input = amount.setScale(decimals.get(from), RoundingMode.DOWN);
  BigDecimal fee = input.multiply(BigDecimal.valueOf(feeBps)).movePointLeft(4).setScale(decimals.get(from), RoundingMode.HALF_UP);
  BigDecimal rate = p.get(from).priceUsd().divide(p.get(to).priceUsd(), 18, RoundingMode.HALF_UP);
  // Round in the platform's favour: the user never receives more than the rate allows.
  BigDecimal output = input.subtract(fee).multiply(rate).setScale(decimals.get(to), RoundingMode.DOWN);
  BigDecimal minimum = output.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(slippageBps).movePointLeft(4))).setScale(decimals.get(to), RoundingMode.DOWN);
  if (input.signum() <= 0 || output.signum() <= 0 || minimum.signum() <= 0) throw ApiException.badRequest("AMOUNT_TOO_SMALL", "Amount is too small to quote");

  Instant now = Instant.now();
  Quote q = new Quote(UUID.randomUUID(), userId, from, to, input, fee, BigDecimal.valueOf(feeBps).movePointLeft(2).stripTrailingZeros().toPlainString() + "%",
   rate.stripTrailingZeros(), output, minimum, slippageBps, BigDecimal.valueOf(slippageBps).movePointLeft(2).stripTrailingZeros().toPlainString() + "%",
   p.get(from).priceUsd(), p.get(to).priceUsd(), now, now.plus(quoteTtl), quoteTtl.toSeconds());
  repo.insertQuote(q, feeBps);
  meters.counter("swap.quotes", "pair", from + "/" + to).increment();
  return q;
 }

 // ---- execution ------------------------------------------------------------------------

 public record ExecuteResult(SwapOrder order, boolean created) {}

 /** Accepts the swap (202) and starts the saga; the outcome arrives asynchronously. */
 public ExecuteResult execute(String userId, UUID quoteId, String idempotencyKey) {
  Optional<SwapOrder> replay = repo.findByIdempotencyKey(userId, idempotencyKey);
  if (replay.isPresent()) {
   if (!replay.get().quoteId().equals(quoteId))
    throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used for a different quote");
   return new ExecuteResult(replay.get(), false);
  }
  UUID orderId = UUID.randomUUID();
  try {
   tx.executeWithoutResult(s -> {
    Map<String, Object> q = repo.lockQuote(quoteId).filter(r -> userId.equals(r.get("user_id")))
     .orElseThrow(() -> ApiException.notFound("QUOTE_NOT_FOUND", "Quote not found"));
    if (((java.sql.Timestamp) q.get("expires_at")).toInstant().isBefore(Instant.now()))
     throw ApiException.conflict("QUOTE_EXPIRED", "Quote expired; request a new one");
    if (repo.quoteAlreadyUsed(quoteId)) throw ApiException.conflict("QUOTE_ALREADY_USED", "This quote was already executed");
    repo.insertOrder(orderId, userId, quoteId, idempotencyKey, q);
    outbox.append("swap", orderId.toString(), CMD_RESERVE, "ReserveFunds",
     new ReserveFunds(orderId.toString(), userId, (String) q.get("from_symbol"), (BigDecimal) q.get("input_amount")));
    publishState(repo.lock(orderId).orElseThrow(), null);
   });
  } catch (org.springframework.dao.DuplicateKeyException e) {
   // A concurrent request won the race: same key -> return its order; same quote, other key -> already used.
   return repo.findByIdempotencyKey(userId, idempotencyKey).map(o -> new ExecuteResult(o, false))
    .orElseThrow(() -> ApiException.conflict("QUOTE_ALREADY_USED", "This quote was already executed"));
  }
  meters.counter("swap.orders", "result", "accepted", "reason", "none").increment();
  return new ExecuteResult(repo.find(userId, orderId).orElseThrow(), true);
 }

 // ---- saga steps (called by SwapSagaListener inside its transaction) ---------------------

 /** Funds are held. Re-price at the live market; settle if still within the minimum, otherwise release. */
 public void onFundsReserved(UUID orderId, Optional<Map<String, MarketClient.Price>> livePrices) {
  SwapOrder o = repo.lock(orderId).orElse(null);
  if (o == null || o.state() != State.CREATED) return;
  if (o.pendingFailureReason() != null) { transition(o, State.PENDING, "Funds reserved after a release was requested; awaiting release"); return; }
  Map<String, MarketClient.Price> p = livePrices.orElse(Map.of());
  MarketClient.Price from = p.get(o.fromSymbol()), to = p.get(o.toSymbol());
  if (from == null || to == null || from.stale() || to.stale()) {
   requestRelease(transition(o, State.PENDING, "Funds reserved; market data unavailable or stale"), "MARKET_UNAVAILABLE");
   return;
  }
  int toDecimals = catalogue().getOrDefault(o.toSymbol(), 8);
  BigDecimal rate = from.priceUsd().divide(to.priceUsd(), 18, RoundingMode.HALF_UP);
  BigDecimal output = o.inputAmount().subtract(o.fee()).multiply(rate).setScale(toDecimals, RoundingMode.DOWN);
  if (output.compareTo(o.minimumReceived()) < 0) {
   SwapOrder pending = transition(o, State.PENDING, "Price moved: would receive %s %s, below minimum %s".formatted(
    output.toPlainString(), o.toSymbol(), o.minimumReceived().toPlainString()));
   requestRelease(pending, "PRICE_MOVED");
   return;
  }
  repo.setExecution(o.orderId(), output, rate, from.priceUsd(), to.priceUsd());
  SwapOrder pending = transition(o, State.PENDING, "Funds reserved; settling %s %s at rate %s".formatted(output.toPlainString(), o.toSymbol(), rate.stripTrailingZeros().toPlainString()));
  outbox.append("swap", o.orderId().toString(), CMD_SETTLE, "SettleSwap", new SettleSwap(o.orderId().toString(), o.userId(), o.fromSymbol(),
   o.inputAmount(), o.fee(), o.toSymbol(), output, from.priceUsd(), to.priceUsd()));
  log.debug("Settle requested for {}", pending.orderId());
 }

 public void onReservationRejected(UUID orderId, String reason, String detail) {
  SwapOrder o = repo.lock(orderId).orElse(null);
  if (o == null || o.state().terminal()) return;
  fail(o, reason, "Reservation rejected: " + (detail == null ? reason : detail));
 }

 public void onSwapSettled(UUID orderId, BigDecimal output, String ledgerEntryId) {
  SwapOrder o = repo.lock(orderId).orElse(null);
  if (o == null || o.state() == State.COMPLETED) return;
  if (o.state() == State.FAILED) {
   // Should be impossible: we only fail after the wallet confirmed a release. Make it loud.
   log.error("Swap {} settled in the wallet but is FAILED here; manual review needed", orderId);
   meters.counter("swap.inconsistencies").increment();
   return;
  }
  repo.setSettled(orderId, output, ledgerEntryId);
  transition(o, State.COMPLETED, "Settled in ledger entry " + ledgerEntryId);
  meters.counter("swap.orders", "result", "completed", "reason", "none").increment();
 }

 public void onSettlementRejected(UUID orderId, String reason) {
  SwapOrder o = repo.lock(orderId).orElse(null);
  if (o == null || o.state().terminal()) return;
  fail(o, reason, "Settlement rejected by wallet: " + reason);
 }

 public void onFundsReleased(UUID orderId, String reason) {
  SwapOrder o = repo.lock(orderId).orElse(null);
  if (o == null || o.state().terminal()) return;
  String finalReason = o.pendingFailureReason() != null ? o.pendingFailureReason() : reason;
  fail(o, finalReason, "Wallet released the held funds (" + finalReason + ")");
 }

 /** Timeout handling: ask the wallet (the authority) to release; never decide the outcome locally. */
 public void onTimeout(UUID orderId) {
  SwapOrder o = repo.lock(orderId).orElse(null);
  if (o == null || o.state().terminal()) return;
  boolean resend = o.releaseRequestedAt() != null;
  requestRelease(o, "TIMEOUT");
  repo.history(o.orderId(), o.state(), o.state(), resend ? "Still waiting for the wallet; release re-requested" : "No progress in time; release requested");
  meters.counter("swap.timeouts", "resend", String.valueOf(resend)).increment();
 }

 // ---- helpers --------------------------------------------------------------------------

 private void requestRelease(SwapOrder o, String reason) {
  repo.requestRelease(o.orderId(), reason);
  outbox.append("swap", o.orderId().toString(), CMD_RELEASE, "ReleaseFunds", new ReleaseFunds(o.orderId().toString(), o.userId(), reason));
 }

 private void fail(SwapOrder o, String reason, String note) {
  repo.setFailure(o.orderId(), reason);
  transition(o, State.FAILED, note);
  log.warn("Swap {} FAILED: {} ({})", o.orderId(), reason, note);
  meters.counter("swap.orders", "result", "failed", "reason", reason).increment();
 }

 private SwapOrder transition(SwapOrder o, State next, String note) {
  if (!o.state().canMoveTo(next)) throw new IllegalStateException("Illegal swap transition " + o.state() + " -> " + next + " for " + o.orderId());
  repo.moveTo(o, next, note);
  log.info("Swap {} {} -> {}: {}", o.orderId(), o.state(), next, note);
  SwapOrder updated = repo.lock(o.orderId()).orElseThrow();
  publishState(updated, o.state());
  return updated;
 }

 private void publishState(SwapOrder o, State previous) {
  SwapOrder withHistory = repo.withHistory(o);
  outbox.append("swap", o.orderId().toString(), EVT_STATE_CHANGED, "SwapStateChanged", new SwapStateChanged(
   o.orderId().toString(), o.userId(), o.state().name(), previous == null ? null : previous.name(), o.failureReason(),
   o.fromSymbol(), o.toSymbol(), o.inputAmount(), o.fee(), o.estimatedOutput(), o.outputAmount(), o.createdAt(), Instant.now(),
   withHistory.stateHistory().stream().map(StateChange::toState).toList()));
 }

 private Map<String, Integer> catalogue() {
  try { return market.decimals(); }
  catch (Exception e) { throw ApiException.unavailable("MARKET_UNAVAILABLE", "Market data is unavailable; try again shortly"); }
 }

 private Map<String, MarketClient.Price> livePrices(List<String> symbols) {
  Map<String, MarketClient.Price> p;
  try { p = market.prices(symbols); }
  catch (Exception e) { throw ApiException.unavailable("MARKET_UNAVAILABLE", "Market data is unavailable; try again shortly"); }
  for (String s : symbols) {
   MarketClient.Price price = p.get(s);
   if (price == null || price.priceUsd() == null || price.priceUsd().signum() <= 0) throw ApiException.unavailable("MARKET_UNAVAILABLE", "No price for " + s);
   if (price.stale()) throw ApiException.unavailable("PRICES_STALE", "Price for " + s + " is " + price.ageSeconds() + "s old; quotes need fresh data");
  }
  return p;
 }
}
