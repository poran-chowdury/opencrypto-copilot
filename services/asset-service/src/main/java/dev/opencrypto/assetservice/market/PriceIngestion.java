package dev.opencrypto.assetservice.market;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The single write path for market data (the "port" both provider adapters call). One batch =
 * one transaction: latest prices, history rows and events become visible together, and the Redis
 * cache is refreshed only after the commit succeeds.
 */
@Service
public class PriceIngestion {
 public record Event(String symbol, BigDecimal shockPct, String headline, String source) {}
 public record HistoryPoint(String symbol, Instant at, BigDecimal priceUsd) {}

 private final JdbcClient jdbc;
 private final JdbcTemplate batch;
 private final PriceService prices;
 private Instant lastCapture = Instant.EPOCH;
 private volatile Instant lastIngestAt = Instant.now();

 public PriceIngestion(JdbcClient jdbc, JdbcTemplate batch, PriceService prices, MeterRegistry meters) {
  this.jdbc = jdbc;
  this.batch = batch;
  this.prices = prices;
  // Alert on this: it keeps growing when the provider fails or the sync job stops.
  Gauge.builder("market.price.age.seconds", () -> java.time.Duration.between(lastIngestAt, Instant.now()).toSeconds())
   .description("Seconds since market prices were last ingested").register(meters);
 }

 /** Snapshot timestamps are the primary key with the symbol, so they must strictly increase. */
 public synchronized Instant nextCaptureTime() {
  Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
  lastCapture = now.isAfter(lastCapture) ? now : lastCapture.plus(1, ChronoUnit.MICROS);
  return lastCapture;
 }

 @Transactional
 public int ingest(String source, Instant capturedAt, List<MarketQuote> quotes, List<Event> events) {
  Timestamp at = Timestamp.from(capturedAt);
  for (MarketQuote q : quotes) {
   jdbc.sql("""
     UPDATE asset SET price_usd = :price, change_24h_pct = :change,
       market_cap_usd = COALESCE(:cap, market_cap_usd), volume_24h_usd = COALESCE(:volume, volume_24h_usd),
       market_cap_rank = COALESCE(:rank, market_cap_rank), image_url = COALESCE(:image, image_url),
       price_source = :source, price_updated_at = :at
     WHERE symbol = :symbol""")
    .param("price", q.priceUsd()).param("change", q.change24hPct()).param("cap", q.marketCapUsd())
    .param("volume", q.volume24hUsd()).param("rank", q.marketCapRank()).param("image", q.imageUrl())
    .param("source", source).param("at", at).param("symbol", q.symbol())
    .update();
  }
  // Cash is pegged; keep it fresh so balances in USD are never reported as stale.
  jdbc.sql("UPDATE asset SET price_usd = 1, price_source = 'PEG', price_updated_at = :at WHERE kind = 'FIAT'").param("at", at).update();
  batch.batchUpdate("INSERT INTO price_snapshot (symbol, captured_at, price_usd, source) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
   quotes, 500, (ps, q) -> { ps.setString(1, q.symbol()); ps.setTimestamp(2, at); ps.setBigDecimal(3, q.priceUsd()); ps.setString(4, source); });
  for (Event e : events) {
   jdbc.sql("INSERT INTO market_event (id, symbol, shock_pct, headline, source, occurred_at) VALUES (:id, :symbol, :pct, :headline, :source, :at)")
    .param("id", UUID.randomUUID()).param("symbol", e.symbol()).param("pct", e.shockPct())
    .param("headline", e.headline()).param("source", e.source()).param("at", at).update();
  }
  refreshCacheAfterCommit();
  return quotes.size();
 }

 /** Bulk history (start-up backfill, CoinGecko sparklines). Existing points are kept. */
 @Transactional
 public void ingestHistory(String source, List<HistoryPoint> points) {
  batch.batchUpdate("INSERT INTO price_snapshot (symbol, captured_at, price_usd, source) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
   points, 1000, (ps, p) -> { ps.setString(1, p.symbol()); ps.setTimestamp(2, Timestamp.from(p.at())); ps.setBigDecimal(3, p.priceUsd()); ps.setString(4, source); });
 }

 @Transactional
 public void deleteSimulatedHistory() {
  jdbc.sql("DELETE FROM price_snapshot WHERE source = 'SIMULATED'").update();
  jdbc.sql("DELETE FROM market_event WHERE source IN ('SIMULATED', 'MANUAL')").update();
 }

 private void refreshCacheAfterCommit() {
  if (!TransactionSynchronizationManager.isSynchronizationActive()) { prices.refreshCache(); lastIngestAt = Instant.now(); return; }
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
   @Override public void afterCommit() { prices.refreshCache(); lastIngestAt = Instant.now(); }
  });
 }
}
