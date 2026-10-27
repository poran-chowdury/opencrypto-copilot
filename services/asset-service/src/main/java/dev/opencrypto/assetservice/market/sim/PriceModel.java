package dev.opencrypto.assetservice.market.sim;

import java.util.*;

/**
 * The stochastic price process shared by the simulated provider and the dataset generator.
 *
 * Each step: a hidden volatility regime may switch (CALM / NORMAL / TURBULENT, so volatility
 * clusters like real markets), then every asset takes a correlated geometric-Brownian step
 * (a common market factor plus idiosyncratic noise), then a fictional news event may jump
 * one asset. Deterministic for a given seed and sequence of calls.
 */
public final class PriceModel {
 public enum Regime {
  CALM(0.6), NORMAL(1.0), TURBULENT(2.2);
  final double volatilityFactor;
  Regime(double f) { this.volatilityFactor = f; }
 }

 public record SimAsset(String symbol, double initialPrice, double annualVolatility, double annualDrift) {}
 public record Jump(String symbol, double shockPct, String headline) {}

 private static final double MINUTES_PER_YEAR = 365.0 * 24 * 60;
 private static final double MEAN_REGIME_DAYS = 5;
 private static final String[] UP_HEADLINES = {
  "Simulated: large inflow into %s reported by a fictional fund",
  "Simulated: %s network upgrade completes ahead of schedule",
  "Simulated: fictional exchange lists new %s trading pairs"};
 private static final String[] DOWN_HEADLINES = {
  "Simulated: rumours of a fictional exchange outage affecting %s",
  "Simulated: large %s holder moves coins to an exchange",
  "Simulated: fictional regulator comments on %s derivatives"};

 private final List<SimAsset> assets;
 private final List<SimAsset> volatile_;
 private final Random random;
 private final double correlation;
 private final Map<String, Double> prices = new LinkedHashMap<>();
 private Regime regime = Regime.NORMAL;

 public PriceModel(List<SimAsset> assets, Random random, double correlation) {
  this.assets = List.copyOf(assets);
  this.volatile_ = assets.stream().filter(a -> a.annualVolatility() > 0).toList();
  this.random = random;
  this.correlation = correlation;
  assets.forEach(a -> prices.put(a.symbol(), a.initialPrice()));
 }

 /** Advance by {@code simulatedMinutes}; {@code eventProbability} is the chance of one news jump. */
 public Optional<Jump> step(double simulatedMinutes, double volatilityMultiplier, double eventProbability) {
  maybeSwitchRegime(simulatedMinutes / (24 * 60));
  double dtYears = simulatedMinutes / MINUTES_PER_YEAR;
  double marketShock = random.nextGaussian();
  for (SimAsset a : assets) {
   if (a.annualVolatility() == 0) continue;
   double z = correlation * marketShock + Math.sqrt(1 - correlation * correlation) * random.nextGaussian();
   double sigma = a.annualVolatility() * volatilityMultiplier * regime.volatilityFactor;
   double logReturn = (a.annualDrift() - 0.5 * sigma * sigma) * dtYears + sigma * Math.sqrt(dtYears) * z;
   prices.compute(a.symbol(), (k, p) -> p * Math.exp(logReturn));
  }
  if (volatile_.isEmpty() || random.nextDouble() >= eventProbability) return Optional.empty();
  SimAsset target = volatile_.get(random.nextInt(volatile_.size()));
  double magnitude = (2 + random.nextDouble() * 6) * (regime == Regime.TURBULENT ? 1.5 : 1.0);
  boolean up = random.nextBoolean();
  String headline = (up ? UP_HEADLINES : DOWN_HEADLINES)[random.nextInt(3)].formatted(target.symbol());
  double pct = up ? magnitude : -magnitude;
  shock(target.symbol(), pct);
  return Optional.of(new Jump(target.symbol(), pct, headline));
 }

 private void maybeSwitchRegime(double dtDays) {
  if (random.nextDouble() >= dtDays / MEAN_REGIME_DAYS) return;
  double r = random.nextDouble();
  regime = switch (regime) {
   case CALM -> r < 0.8 ? Regime.NORMAL : Regime.TURBULENT;
   case NORMAL -> r < 0.6 ? Regime.CALM : Regime.TURBULENT;
   case TURBULENT -> r < 0.8 ? Regime.NORMAL : Regime.CALM;
  };
 }

 public void shock(String symbol, double pct) { prices.computeIfPresent(symbol, (k, p) -> p * (1 + pct / 100.0)); }

 public Map<String, Double> prices() { return Collections.unmodifiableMap(prices); }

 public Regime regime() { return regime; }

 public List<SimAsset> assets() { return assets; }
}
