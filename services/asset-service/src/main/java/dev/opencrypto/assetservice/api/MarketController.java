package dev.opencrypto.assetservice.api;

import java.time.Instant;
import java.util.*;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import dev.opencrypto.assetservice.catalog.AssetRepository;
import dev.opencrypto.assetservice.catalog.CatalogAsset;
import dev.opencrypto.assetservice.market.HistoryRepository;
import dev.opencrypto.assetservice.market.MarketState;
import dev.opencrypto.assetservice.market.SyncRunRepository;
import dev.opencrypto.assetservice.market.sim.DatasetGenerator;
import dev.opencrypto.assetservice.market.sim.MarketSimulator;
import dev.opencrypto.assetservice.market.sim.PriceModel;
import dev.opencrypto.assetservice.market.sim.SimulatedMarketDriver;
import dev.opencrypto.platform.web.ApiException;

/** Market status, timeline data, simulation controls (simulated provider only) and offline datasets. */
@RestController
@RequestMapping("/api/v1/market")
@Validated
public class MarketController {
 private final MarketState state;
 private final HistoryRepository history;
 private final SyncRunRepository runs;
 private final AssetRepository assets;
 private final ObjectProvider<SimulatedMarketDriver> simulated;
 private final ObjectProvider<CircuitBreakerRegistry> breakers;

 public MarketController(MarketState state, HistoryRepository history, SyncRunRepository runs, AssetRepository assets,
   ObjectProvider<SimulatedMarketDriver> simulated, ObjectProvider<CircuitBreakerRegistry> breakers) {
  this.state = state;
  this.history = history;
  this.runs = runs;
  this.assets = assets;
  this.simulated = simulated;
  this.breakers = breakers;
 }

 /** Operational view: provider, last sync, circuit breaker, freshness. */
 @GetMapping("/status")
 public Map<String, Object> status() {
  Map<String, Object> m = new LinkedHashMap<>();
  m.put("provider", state.provider());
  m.put("priceFeed", state.feedUp() ? "UP" : "DOWN");
  m.put("staleAfterSeconds", state.staleAfterSeconds());
  runs.latest().ifPresent(r -> m.put("lastSync", r));
  runs.latestSuccessful().ifPresent(r -> m.put("lastSuccessfulSync", r));
  CircuitBreakerRegistry registry = breakers.getIfAvailable();
  if (registry != null) registry.find("coingecko").ifPresent(cb -> m.put("circuitBreaker", Map.of(
   "name", cb.getName(), "state", cb.getState().name(), "failureRate", cb.getMetrics().getFailureRate(),
   "bufferedCalls", cb.getMetrics().getNumberOfBufferedCalls())));
  SimulatedMarketDriver d = simulated.getIfAvailable();
  if (d != null) m.put("simulation", simulationStatus(d));
  return m;
 }

 @GetMapping("/snapshots")
 public Map<String, Object> snapshots(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
   @RequestParam(defaultValue = "200") @Min(1) @Max(5000) int limit) {
  state.requireFeed();
  List<HistoryRepository.Snapshot> list = history.snapshots(from, to, limit);
  return Map.of("snapshots", list, "count", list.size(), "provider", state.provider());
 }

 @GetMapping("/events")
 public List<HistoryRepository.Event> events(@RequestParam(required = false) String symbol,
   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
   @RequestParam(defaultValue = "50") @Min(1) @Max(1000) int limit) {
  return history.events(symbol == null || symbol.isBlank() ? null : symbol.toUpperCase(Locale.ROOT), from, to, limit);
 }

 @PostMapping("/events")
 public Map<String, Object> inject(@Valid @RequestBody ShockRequest r) {
  driver().shock(r.symbol(), r.shockPct(), r.headline());
  return Map.of("symbol", r.symbol().toUpperCase(Locale.ROOT), "shockPct", r.shockPct(), "appliedAt", Instant.now());
 }

 @GetMapping("/simulation")
 public Map<String, Object> simulation() { return simulationStatus(driver()); }

 @PostMapping("/simulation")
 public Map<String, Object> update(@RequestBody SimulationUpdate r) {
  SimulatedMarketDriver d = driver();
  d.simulator().update(r.running(), r.volatilityMultiplier(), r.eventProbabilityPerTick(), r.tickIntervalMs());
  if (r.priceFeed() != null) {
   if (!r.priceFeed().equals("UP") && !r.priceFeed().equals("DOWN")) throw ApiException.badRequest("INVALID_SETTING", "priceFeed must be UP or DOWN");
   state.feedUp(r.priceFeed().equals("UP"));
  }
  if (r.staleAfterSeconds() != null) {
   if (r.staleAfterSeconds() < 1 || r.staleAfterSeconds() > 86400) throw ApiException.badRequest("INVALID_SETTING", "staleAfterSeconds must be between 1 and 86400");
   state.staleAfterSeconds(r.staleAfterSeconds());
  }
  return simulationStatus(d);
 }

 @PostMapping("/simulation/step")
 public Map<String, Object> step(@RequestParam(defaultValue = "1") int ticks) {
  SimulatedMarketDriver d = driver();
  d.step(ticks);
  return simulationStatus(d);
 }

 @PostMapping("/simulation/reset")
 public Map<String, Object> reset(@RequestBody(required = false) ResetRequest r) {
  SimulatedMarketDriver d = driver();
  d.reset(r == null ? null : r.seed(), r == null || r.running() == null || r.running());
  return simulationStatus(d);
 }

 /** Offline dataset, e.g. /api/v1/market/dataset?seed=42&days=365&intervalMinutes=60&includeRegime=true */
 @GetMapping("/dataset")
 public ResponseEntity<?> dataset(@RequestParam(defaultValue = "42") long seed, @RequestParam(defaultValue = "90") int days,
   @RequestParam(defaultValue = "60") int intervalMinutes, @RequestParam(defaultValue = "1") double eventsPerDay,
   @RequestParam(defaultValue = "false") boolean includeRegime, @RequestParam(defaultValue = "csv") String format) {
  List<PriceModel.SimAsset> simAssets = new ArrayList<>();
  for (CatalogAsset a : assets.findAllActive())
   if (!a.isFiat()) simAssets.add(new PriceModel.SimAsset(a.symbol(), a.simInitialPrice().doubleValue(), a.simAnnualVolatility().doubleValue(), a.simAnnualDrift().doubleValue()));
  List<DatasetGenerator.Row> rows = DatasetGenerator.generate(simAssets, seed, days, intervalMinutes, eventsPerDay);
  if (format.equalsIgnoreCase("json")) {
   return ResponseEntity.ok(Map.of("seed", seed, "days", days, "intervalMinutes", intervalMinutes, "rows", rows.stream().map(r -> {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("at", r.at());
    Map<String, Object> p = new TreeMap<>();
    r.prices().forEach((k, v) -> p.put(k, DatasetGenerator.price(v)));
    m.put("prices", p);
    if (includeRegime) m.put("regime", r.regime());
    if (r.event() != null) m.put("event", Map.of("symbol", r.event().symbol(), "shockPct", r.event().shockPct(), "headline", r.event().headline()));
    return m;
   }).toList()));
  }
  return ResponseEntity.ok().contentType(new MediaType("text", "csv"))
   .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"opencrypto-sim-seed%d-%dd-%dm.csv\"".formatted(seed, days, intervalMinutes))
   .body(DatasetGenerator.toCsv(simAssets, rows, includeRegime));
 }

 private SimulatedMarketDriver driver() {
  SimulatedMarketDriver d = simulated.getIfAvailable();
  if (d == null) throw ApiException.conflict("SIMULATION_DISABLED", "Simulation controls need MARKET_PROVIDER=simulated (current provider: " + state.provider() + ")");
  return d;
 }

 private Map<String, Object> simulationStatus(SimulatedMarketDriver d) {
  MarketSimulator s = d.simulator();
  Map<String, Object> m = new LinkedHashMap<>();
  m.put("running", s.running());
  m.put("priceFeed", state.feedUp() ? "UP" : "DOWN");
  m.put("ticks", s.ticks());
  m.put("tickIntervalMs", s.tickIntervalMs());
  m.put("simulatedMinutesPerTick", s.simulatedMinutesPerTick());
  m.put("volatilityMultiplier", s.volatilityMultiplier());
  m.put("eventProbabilityPerTick", s.eventProbabilityPerTick());
  m.put("staleAfterSeconds", state.staleAfterSeconds());
  m.put("seed", s.seed());
  return m;
 }

 public record ShockRequest(@NotBlank String symbol, @NotNull Double shockPct, @Size(max = 200) String headline) {}
 public record SimulationUpdate(Boolean running, Double volatilityMultiplier, Double eventProbabilityPerTick, Long tickIntervalMs,
   String priceFeed, Long staleAfterSeconds) {}
 public record ResetRequest(Long seed, Boolean running) {}
}
