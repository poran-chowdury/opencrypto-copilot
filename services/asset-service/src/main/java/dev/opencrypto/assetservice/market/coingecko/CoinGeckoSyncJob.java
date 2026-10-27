package dev.opencrypto.assetservice.market.coingecko;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import dev.opencrypto.assetservice.catalog.AssetRepository;
import dev.opencrypto.assetservice.catalog.CatalogAsset;
import dev.opencrypto.assetservice.market.MarketQuote;
import dev.opencrypto.assetservice.market.PriceIngestion;
import dev.opencrypto.assetservice.market.SyncRunRepository;

/**
 * Pulls market data from CoinGecko on a fixed delay. ShedLock makes sure only one instance syncs
 * per interval when asset-service is scaled out (otherwise N instances = N times the API quota).
 * A failed sync leaves the last good prices in place; readers see them flagged as stale.
 */
@Component
@ConditionalOnProperty(name = "market.provider", havingValue = "coingecko", matchIfMissing = true)
public class CoinGeckoSyncJob {
 private static final Logger log = LoggerFactory.getLogger(CoinGeckoSyncJob.class);
 public static final String SOURCE = "COINGECKO";

 private final CoinGeckoClient client;
 private final AssetRepository assets;
 private final PriceIngestion ingestion;
 private final SyncRunRepository runs;
 private final Timer timer;
 private volatile boolean backfilled;

 public CoinGeckoSyncJob(CoinGeckoClient client, AssetRepository assets, PriceIngestion ingestion, SyncRunRepository runs, MeterRegistry meters) {
  this.client = client;
  this.assets = assets;
  this.ingestion = ingestion;
  this.runs = runs;
  this.timer = Timer.builder("market.sync").tag("provider", "coingecko").register(meters);
 }

 @Scheduled(fixedDelayString = "${market.coingecko.sync-interval:PT60S}", initialDelayString = "${market.coingecko.initial-delay:PT2S}")
 @SchedulerLock(name = "coingecko-sync", lockAtMostFor = "PT5M", lockAtLeastFor = "PT15S")
 public void sync() {
  timer.record(this::syncOnce);
 }

 /** One sync without the cluster lock (the scheduled entry point is {@link #sync()}). */
 public void syncOnce() {
  Map<String, CatalogAsset> byId = new HashMap<>();
  for (CatalogAsset a : assets.findAllActive()) if (a.coingeckoId() != null) byId.put(a.coingeckoId(), a);
  long run = runs.start("coingecko");
  try {
   List<CoinGeckoClient.Market> markets = client.markets(List.copyOf(byId.keySet()));
   List<MarketQuote> quotes = new ArrayList<>();
   for (CoinGeckoClient.Market m : markets) {
    CatalogAsset a = byId.get(m.id());
    if (a == null || m.currentPrice() == null) continue;
    quotes.add(new MarketQuote(a.symbol(), m.currentPrice(), m.marketCap(), m.totalVolume(), m.priceChangePercentage24h(), m.marketCapRank(), m.image()));
   }
   ingestion.ingest(SOURCE, ingestion.nextCaptureTime(), quotes, List.of());
   if (!backfilled) { backfillFromSparklines(markets, byId); backfilled = true; }
   runs.finish(run, quotes.size());
   log.info("CoinGecko sync stored {} prices", quotes.size());
  } catch (CallNotPermittedException e) {
   runs.fail(run, "Circuit breaker open; skipping call to protect the upstream");
   log.warn("CoinGecko circuit open, serving last known prices");
  } catch (Exception e) {
   runs.fail(run, e.getClass().getSimpleName() + ": " + e.getMessage());
   log.warn("CoinGecko sync failed: {}", e.getMessage());
  }
 }

 /**
 * First sync after start-up: turn the 7-day hourly sparkline into history so charts are useful
 * immediately. Points land on whole hours, so every asset shares the same timestamps.
 */
 private void backfillFromSparklines(List<CoinGeckoClient.Market> markets, Map<String, CatalogAsset> byId) {
  Instant lastHour = Instant.now().truncatedTo(ChronoUnit.HOURS);
  List<PriceIngestion.HistoryPoint> points = new ArrayList<>();
  for (CoinGeckoClient.Market m : markets) {
   CatalogAsset a = byId.get(m.id());
   if (a == null || m.sparkline() == null || m.sparkline().price() == null) continue;
   List<BigDecimal> series = m.sparkline().price();
   for (int i = 0; i < series.size(); i++) {
    if (series.get(i) == null) continue;
    points.add(new PriceIngestion.HistoryPoint(a.symbol(), lastHour.minus(series.size() - 1 - i, ChronoUnit.HOURS), series.get(i)));
   }
  }
  ingestion.ingestHistory(SOURCE, points);
  log.info("Backfilled {} hourly history points from CoinGecko sparklines", points.size());
 }
}
