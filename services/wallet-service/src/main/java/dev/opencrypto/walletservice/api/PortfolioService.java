package dev.opencrypto.walletservice.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import dev.opencrypto.walletservice.ledger.LedgerService;
import dev.opencrypto.walletservice.ledger.PositionService;
import dev.opencrypto.walletservice.ledger.PositionService.Position;
import dev.opencrypto.walletservice.ledger.WalletService;
import dev.opencrypto.walletservice.pricing.AssetClient;

/** Values balances at current prices and computes unrealised/realised P/L from positions. */
@Service
public class PortfolioService {
 private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);

 public record Holding(String symbol, BigDecimal available, BigDecimal held, BigDecimal balance, BigDecimal priceUsd, BigDecimal change24hPct,
   boolean priceStale, BigDecimal valueUsd, BigDecimal allocationPct, BigDecimal costBasisUsd, BigDecimal averageCostUsd,
   BigDecimal unrealizedPnlUsd, BigDecimal unrealizedPnlPct, BigDecimal realizedPnlUsd) {}

 public record Portfolio(String userId, Instant asOf, boolean pricesAvailable, List<String> stalePrices, BigDecimal totalValueUsd,
   BigDecimal totalCostBasisUsd, BigDecimal unrealizedPnlUsd, BigDecimal realizedPnlUsd, BigDecimal totalPnlUsd, BigDecimal totalPnlPct,
   List<Holding> assets) {}

 public record TimelinePoint(Instant at, BigDecimal valueUsd) {}

 private final LedgerService ledger;
 private final PositionService positions;
 private final WalletService wallet;
 private final AssetClient assets;

 public PortfolioService(LedgerService ledger, PositionService positions, WalletService wallet, AssetClient assets) {
  this.ledger = ledger;
  this.positions = positions;
  this.wallet = wallet;
  this.assets = assets;
 }

 public Portfolio portfolio(String userId) {
  wallet.ensureOpened(userId);
  Map<String, Map<String, BigDecimal>> balances = ledger.balances(userId);
  Map<String, Position> pos = positions.positions(userId);
  Map<String, AssetClient.Price> prices = Map.of();
  boolean pricesAvailable = true;
  try { prices = assets.prices(); } catch (Exception e) { pricesAvailable = false; log.warn("Prices unavailable: {}", e.getMessage()); }

  Set<String> symbols = new TreeSet<>(balances.keySet());
  pos.forEach((k, p) -> { if (p.quantity().signum() != 0 || p.realizedPnlUsd().signum() != 0) symbols.add(k); });
  List<Holding> holdings = new ArrayList<>();
  List<String> stale = new ArrayList<>();
  BigDecimal total = BigDecimal.ZERO, cost = BigDecimal.ZERO, unrealized = BigDecimal.ZERO, realized = BigDecimal.ZERO;
  for (String s : symbols) {
   BigDecimal available = balances.getOrDefault(s, Map.of()).getOrDefault("AVAILABLE", BigDecimal.ZERO);
   BigDecimal held = balances.getOrDefault(s, Map.of()).getOrDefault("HELD", BigDecimal.ZERO);
   BigDecimal balance = available.add(held);
   Position p = pos.getOrDefault(s, new Position(s, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
   realized = realized.add(p.realizedPnlUsd());
   if (balance.signum() == 0 && p.realizedPnlUsd().signum() == 0) continue;
   AssetClient.Price price = prices.get(s);
   BigDecimal value = price == null ? null : balance.multiply(price.priceUsd()).setScale(2, RoundingMode.HALF_UP);
   BigDecimal pnl = value == null ? null : value.subtract(p.costBasisUsd()).setScale(2, RoundingMode.HALF_UP);
   if (price != null && price.stale()) stale.add(s);
   if (value != null) { total = total.add(value); cost = cost.add(p.costBasisUsd()); unrealized = unrealized.add(pnl); }
   holdings.add(new Holding(s, strip(available), strip(held), strip(balance), price == null ? null : strip(price.priceUsd()),
    price == null ? null : price.change24hPct(), price != null && price.stale(), value, null,
    p.costBasisUsd().setScale(2, RoundingMode.HALF_UP), p.averageCostUsd().setScale(8, RoundingMode.HALF_UP).stripTrailingZeros(),
    pnl, pct(pnl, p.costBasisUsd()), p.realizedPnlUsd().setScale(2, RoundingMode.HALF_UP)));
  }
  BigDecimal finalTotal = total;
  List<Holding> withAllocation = holdings.stream().map(h -> new Holding(h.symbol(), h.available(), h.held(), h.balance(), h.priceUsd(),
   h.change24hPct(), h.priceStale(), h.valueUsd(), h.valueUsd() == null || finalTotal.signum() == 0 ? null
    : h.valueUsd().multiply(BigDecimal.valueOf(100)).divide(finalTotal, 2, RoundingMode.HALF_UP),
   h.costBasisUsd(), h.averageCostUsd(), h.unrealizedPnlUsd(), h.unrealizedPnlPct(), h.realizedPnlUsd()))
   .sorted(Comparator.comparing((Holding h) -> h.valueUsd() == null ? BigDecimal.ZERO : h.valueUsd()).reversed()).toList();
  BigDecimal totalPnl = unrealized.add(realized).setScale(2, RoundingMode.HALF_UP);
  return new Portfolio(userId, Instant.now(), pricesAvailable && !prices.isEmpty(), stale, total.setScale(2, RoundingMode.HALF_UP),
   cost.setScale(2, RoundingMode.HALF_UP), unrealized.setScale(2, RoundingMode.HALF_UP), realized.setScale(2, RoundingMode.HALF_UP),
   totalPnl, pct(totalPnl, cost), withAllocation);
 }

 /** Portfolio value at each price snapshot, replaying the user's postings in time order. */
 public Map<String, Object> timeline(String userId, Instant from, Instant to, int limit) {
  wallet.ensureOpened(userId);
  List<Object[]> postings = ledger.userPostingsChronological(userId);
  List<AssetClient.Snapshot> snapshots;
  boolean available = true;
  try { snapshots = assets.snapshots(limit, from, to); } catch (Exception e) { snapshots = List.of(); available = false; }
  Map<String, BigDecimal> balances = new HashMap<>();
  int next = 0;
  Instant opened = postings.isEmpty() ? null : (Instant) postings.getFirst()[0];
  List<TimelinePoint> points = new ArrayList<>();
  for (AssetClient.Snapshot s : snapshots) {
   while (next < postings.size() && !((Instant) postings.get(next)[0]).isAfter(s.at())) {
    balances.merge((String) postings.get(next)[1], (BigDecimal) postings.get(next)[2], BigDecimal::add);
    next++;
   }
   if (opened == null || s.at().isBefore(opened)) continue;
   BigDecimal value = BigDecimal.ZERO;
   for (var b : balances.entrySet()) {
    BigDecimal price = "USD".equals(b.getKey()) ? BigDecimal.ONE : s.prices().get(b.getKey());
    if (price != null) value = value.add(b.getValue().multiply(price));
   }
   points.add(new TimelinePoint(s.at(), value.setScale(2, RoundingMode.HALF_UP)));
  }
  Instant windowStart = points.isEmpty() ? (from != null ? from : Instant.now()) : points.getFirst().at();
  Instant windowEnd = to != null ? to : Instant.MAX;
  List<Map<String, Object>> events = ledger.userEntries(userId, null, 500, 0).stream()
   .filter(e -> !e.createdAt().isBefore(windowStart) && !e.createdAt().isAfter(windowEnd)
    && !e.type().equals("SWAP_RESERVE") && !e.type().equals("SWAP_RELEASE"))
   .map(e -> Map.<String, Object>of("at", e.createdAt(), "type", e.type(), "description", e.description(), "referenceId", Objects.toString(e.referenceId(), "")))
   .sorted(Comparator.comparing(m -> (Instant) m.get("at"))).toList();
  Map<String, Object> out = new LinkedHashMap<>();
  out.put("userId", userId);
  out.put("walletOpenedAt", opened);
  out.put("pricesAvailable", available);
  out.put("points", points);
  out.put("ledgerEvents", events);
  return out;
 }

 private static BigDecimal pct(BigDecimal pnl, BigDecimal base) {
  return pnl == null || base == null || base.signum() == 0 ? null : pnl.multiply(BigDecimal.valueOf(100)).divide(base, 2, RoundingMode.HALF_UP);
 }

 private static BigDecimal strip(BigDecimal v) { return v == null ? null : v.stripTrailingZeros(); }
}
