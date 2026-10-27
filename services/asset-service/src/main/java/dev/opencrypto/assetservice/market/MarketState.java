package dev.opencrypto.assetservice.market;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import dev.opencrypto.platform.web.ApiException;

/**
 * Which provider is active and the freshness rules that follow from it. Real data arrives
 * every minute, so it is stale after a few minutes; simulated ticks arrive every few seconds.
 */
@Component
public class MarketState {
 public static final String COINGECKO = "coingecko";
 public static final String SIMULATED = "simulated";

 private final String provider;
 private volatile long staleAfterSeconds;
 private volatile boolean feedUp = true;

 public MarketState(@Value("${market.provider:coingecko}") String provider,
   @Value("${market.coingecko.stale-after-seconds:300}") long coingeckoStaleAfter,
   @Value("${market.simulated.stale-after-seconds:30}") long simulatedStaleAfter) {
  if (!provider.equals(COINGECKO) && !provider.equals(SIMULATED))
   throw new IllegalStateException("market.provider must be 'coingecko' or 'simulated', was '" + provider + "'");
  this.provider = provider;
  this.staleAfterSeconds = provider.equals(COINGECKO) ? coingeckoStaleAfter : simulatedStaleAfter;
 }

 public String provider() { return provider; }
 public boolean simulated() { return SIMULATED.equals(provider); }
 public long staleAfterSeconds() { return staleAfterSeconds; }
 public void staleAfterSeconds(long seconds) { this.staleAfterSeconds = seconds; }
 public boolean feedUp() { return feedUp; }
 public void feedUp(boolean up) { this.feedUp = up; }

 /** Simulated outage switch: price endpoints fail like a dead upstream would. */
 public void requireFeed() {
  if (!feedUp) throw ApiException.unavailable("PRICE_FEED_DOWN", "Simulated price feed outage (priceFeed=DOWN)");
 }
}
