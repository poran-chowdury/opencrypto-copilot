package dev.opencrypto.assetservice.market.sim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import dev.opencrypto.assetservice.catalog.AssetRepository;
import dev.opencrypto.assetservice.catalog.CatalogAsset;
import dev.opencrypto.assetservice.market.MarketQuote;
import dev.opencrypto.assetservice.market.MarketState;
import dev.opencrypto.assetservice.market.PriceIngestion;

/**
 * The simulated market-data adapter: ticks {@link MarketSimulator} on a schedule and writes each
 * tick through the same {@link PriceIngestion} path as CoinGecko. Active only with
 * {@code market.provider=simulated}; used for offline development, tests and reproducible AI evals.
 */
@Component
@ConditionalOnProperty(name = "market.provider", havingValue = "simulated")
public class SimulatedMarketDriver {
 public static final String SOURCE = "SIMULATED";
 private static final int CHANGE_WINDOW_TICKS = 100;
 private static final Logger log = LoggerFactory.getLogger(SimulatedMarketDriver.class);

 private final MarketSimulator sim;
 private final PriceIngestion ingestion;
 private final MarketState state;
 private final int backfillTicks;
 private final Deque<Map<String, Double>> recent = new ArrayDeque<>();
 private long lastTickMillis;

 public SimulatedMarketDriver(AssetRepository assets, PriceIngestion ingestion, MarketState state,
   @Value("${market.simulated.seed:#{null}}") Long seed,
   @Value("${market.simulated.tick-interval-ms:2000}") long tickIntervalMs,
   @Value("${market.simulated.simulated-minutes-per-tick:10}") double simulatedMinutesPerTick,
   @Value("${market.simulated.event-probability-per-tick:0.003}") double eventProbability,
   @Value("${market.simulated.backfill-ticks:1800}") int backfillTicks) {
  List<PriceModel.SimAsset> simAssets = new ArrayList<>();
  for (CatalogAsset a : assets.findAllActive()) {
   if (a.isFiat()) continue;
   simAssets.add(new PriceModel.SimAsset(a.symbol(), a.simInitialPrice().doubleValue(), a.simAnnualVolatility().doubleValue(), a.simAnnualDrift().doubleValue()));
  }
  this.sim = new MarketSimulator(simAssets, new MarketSimulator.Settings(seed, tickIntervalMs, simulatedMinutesPerTick, 0.7, eventProbability));
  this.ingestion = ingestion;
  this.state = state;
  this.backfillTicks = backfillTicks;
 }

 @EventListener(ApplicationReadyEvent.class)
 public void start() { reset(sim.seed(), true); }

 /** Wipe simulated history, re-seed, and back-fill so charts have data immediately. */
 public synchronized void reset(Long seed, boolean running) {
  sim.reset(seed);
  sim.update(running, null, null, null);
  state.feedUp(true);
  recent.clear();
  ingestion.deleteSimulatedHistory();
  long interval = sim.tickIntervalMs();
  Instant start = Instant.now().minusMillis(interval * backfillTicks);
  List<PriceIngestion.HistoryPoint> points = new ArrayList<>();
  for (int i = 0; i < backfillTicks; i++) {
   MarketSimulator.Tick t = sim.advance();
   Instant at = start.plusMillis(interval * i);
   remember(t.prices());
   t.prices().forEach((symbol, price) -> points.add(new PriceIngestion.HistoryPoint(symbol, at, money(price))));
  }
  ingestion.ingestHistory(SOURCE, points);
  persist(sim.advance());
  lastTickMillis = System.currentTimeMillis();
  log.info("Simulated market reset (seed={}, backfilled {} ticks)", seed, backfillTicks);
 }

 @Scheduled(fixedDelay = 250)
 public synchronized void heartbeat() {
  long now = System.currentTimeMillis();
  if (!sim.running() || now - lastTickMillis < sim.tickIntervalMs()) return;
  lastTickMillis = now;
  persist(sim.advance());
 }

 public synchronized void step(int ticks) {
  if (ticks < 1 || ticks > 5000) throw dev.opencrypto.platform.web.ApiException.badRequest("INVALID_STEP", "ticks must be between 1 and 5000");
  for (int i = 0; i < ticks; i++) persist(sim.advance());
 }

 public synchronized void shock(String symbol, double pct, String headline) {
  MarketSimulator.Tick t = sim.shock(symbol, pct);
  String text = headline == null || headline.isBlank() ? "Manual shock: %s %+.2f%%".formatted(symbol.toUpperCase(Locale.ROOT), pct) : headline;
  persist(t, List.of(new PriceIngestion.Event(symbol.toUpperCase(Locale.ROOT), BigDecimal.valueOf(pct).setScale(2, RoundingMode.HALF_UP), text, "MANUAL")));
 }

 private void persist(MarketSimulator.Tick t) {
  List<PriceIngestion.Event> events = t.jump().map(j -> List.of(new PriceIngestion.Event(j.symbol(),
   BigDecimal.valueOf(j.shockPct()).setScale(2, RoundingMode.HALF_UP), j.headline(), SOURCE))).orElse(List.of());
  persist(t, events);
 }

 private void persist(MarketSimulator.Tick t, List<PriceIngestion.Event> events) {
  remember(t.prices());
  Map<String, Double> baseline = recent.peekFirst();
  List<MarketQuote> quotes = new ArrayList<>();
  t.prices().forEach((symbol, price) -> {
   BigDecimal change = baseline == null ? null : BigDecimal.valueOf((price / baseline.get(symbol) - 1) * 100).setScale(4, RoundingMode.HALF_UP);
   quotes.add(new MarketQuote(symbol, money(price), null, null, change, null, null));
  });
  ingestion.ingest(SOURCE, ingestion.nextCaptureTime(), quotes, events);
 }

 /** Simulated "24h change" is measured over the last 100 ticks: simulated time runs faster than real time. */
 private void remember(Map<String, Double> prices) {
  recent.addLast(prices);
  while (recent.size() > CHANGE_WINDOW_TICKS + 1) recent.removeFirst();
 }

 private static BigDecimal money(double price) {
  return BigDecimal.valueOf(price).setScale(price < 10 ? 8 : 4, RoundingMode.HALF_UP);
 }

 public MarketSimulator simulator() { return sim; }
}
