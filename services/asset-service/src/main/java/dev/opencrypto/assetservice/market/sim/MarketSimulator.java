package dev.opencrypto.assetservice.market.sim;

import java.util.*;
import dev.opencrypto.platform.web.ApiException;

/**
 * In-memory state of the simulated market: the price model plus its controls (pause, volatility,
 * event rate, tick interval, seed). Pure logic, no I/O, so it is unit-testable;
 * {@link SimulatedMarketDriver} schedules it and persists each tick.
 */
public class MarketSimulator {
 public record Tick(Map<String, Double> prices, Optional<PriceModel.Jump> jump) {}
 public record Settings(Long seed, long tickIntervalMs, double simulatedMinutesPerTick, double correlation, double eventProbabilityPerTick) {}

 private final List<PriceModel.SimAsset> assets;
 private final double simulatedMinutesPerTick, correlation;
 private PriceModel model;
 private Long seed;
 private boolean running = true;
 private long tickIntervalMs;
 private double volatilityMultiplier = 1.0;
 private double eventProbabilityPerTick;
 private long ticks;

 public MarketSimulator(List<PriceModel.SimAsset> assets, Settings s) {
  this.assets = List.copyOf(assets);
  this.simulatedMinutesPerTick = s.simulatedMinutesPerTick();
  this.correlation = s.correlation();
  this.tickIntervalMs = s.tickIntervalMs();
  this.eventProbabilityPerTick = s.eventProbabilityPerTick();
  reset(s.seed());
 }

 public synchronized void reset(Long seed) {
  this.seed = seed;
  this.model = new PriceModel(assets, seed == null ? new Random() : new Random(seed), correlation);
  this.volatilityMultiplier = 1.0;
  this.ticks = 0;
 }

 public synchronized Tick advance() {
  Optional<PriceModel.Jump> jump = model.step(simulatedMinutesPerTick, volatilityMultiplier, eventProbabilityPerTick);
  ticks++;
  return new Tick(Map.copyOf(model.prices()), jump);
 }

 public synchronized Tick shock(String symbol, double pct) {
  PriceModel.SimAsset asset = assets.stream().filter(a -> a.symbol().equalsIgnoreCase(symbol)).findFirst()
   .orElseThrow(() -> ApiException.notFound("UNKNOWN_ASSET", "Unknown asset: " + symbol));
  if (asset.annualVolatility() == 0) throw ApiException.badRequest("FIXED_PRICE_ASSET", asset.symbol() + " has a fixed price in this simulation");
  if (pct <= -95 || pct > 500) throw ApiException.badRequest("INVALID_SHOCK", "shockPct must be > -95 and <= 500");
  model.shock(asset.symbol(), pct);
  ticks++;
  return new Tick(Map.copyOf(model.prices()), Optional.empty());
 }

 public synchronized void update(Boolean running, Double volatilityMultiplier, Double eventProbabilityPerTick, Long tickIntervalMs) {
  if (running != null) this.running = running;
  if (volatilityMultiplier != null) {
   if (volatilityMultiplier < 0 || volatilityMultiplier > 10) throw ApiException.badRequest("INVALID_SETTING", "volatilityMultiplier must be between 0 and 10");
   this.volatilityMultiplier = volatilityMultiplier;
  }
  if (eventProbabilityPerTick != null) {
   if (eventProbabilityPerTick < 0 || eventProbabilityPerTick > 1) throw ApiException.badRequest("INVALID_SETTING", "eventProbabilityPerTick must be between 0 and 1");
   this.eventProbabilityPerTick = eventProbabilityPerTick;
  }
  if (tickIntervalMs != null) {
   if (tickIntervalMs < 250 || tickIntervalMs > 60000) throw ApiException.badRequest("INVALID_SETTING", "tickIntervalMs must be between 250 and 60000");
   this.tickIntervalMs = tickIntervalMs;
  }
 }

 public synchronized boolean running() { return running; }
 public synchronized long tickIntervalMs() { return tickIntervalMs; }
 public synchronized double volatilityMultiplier() { return volatilityMultiplier; }
 public synchronized double eventProbabilityPerTick() { return eventProbabilityPerTick; }
 public synchronized Long seed() { return seed; }
 public synchronized long ticks() { return ticks; }
 public double simulatedMinutesPerTick() { return simulatedMinutesPerTick; }
 public List<PriceModel.SimAsset> assets() { return assets; }
}
