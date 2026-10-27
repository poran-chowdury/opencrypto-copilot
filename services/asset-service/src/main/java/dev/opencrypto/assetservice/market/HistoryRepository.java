package dev.opencrypto.assetservice.market;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Price history reads. Charts never need every raw point, so series are down-sampled in SQL with
 * {@code date_bin} into at most {@code maxPoints} buckets; the response size stays constant
 * whether the range is an hour or a month.
 */
@Repository
public class HistoryRepository {
 public record Point(Instant at, BigDecimal priceUsd) {}
 public record Snapshot(Instant at, Map<String, BigDecimal> prices) {}
 public record Event(UUID id, String symbol, BigDecimal shockPct, String headline, String source, Instant at) {}

 private final JdbcClient jdbc;

 public HistoryRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

 public List<Point> history(String symbol, Instant from, Instant to, int maxPoints) {
  long bucket = Math.max(1, (long) Math.ceil(Duration.between(from, to).toSeconds() / (double) Math.max(1, maxPoints)));
  return jdbc.sql("""
    SELECT date_bin(:bucket * INTERVAL '1 second', captured_at, TIMESTAMPTZ '2000-01-01') AS t, avg(price_usd) AS p
    FROM price_snapshot WHERE symbol = :symbol AND captured_at BETWEEN :from AND :to
    GROUP BY t ORDER BY t""")
   .param("bucket", bucket).param("symbol", symbol).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
   .query((rs, i) -> new Point(rs.getTimestamp("t").toInstant(), rs.getBigDecimal("p"))).list();
 }

 /** Small down-sampled series per symbol for list views (sparklines). */
 public Map<String, List<BigDecimal>> sparklines(Collection<String> symbols, Duration window, int points) {
  if (symbols.isEmpty()) return Map.of();
  long bucket = Math.max(1, window.toSeconds() / points);
  Map<String, List<BigDecimal>> out = new HashMap<>();
  jdbc.sql("""
    SELECT symbol, date_bin(:bucket * INTERVAL '1 second', captured_at, TIMESTAMPTZ '2000-01-01') AS t, avg(price_usd) AS p
    FROM price_snapshot WHERE symbol IN (:symbols) AND captured_at >= :from
    GROUP BY symbol, t ORDER BY symbol, t""")
   .param("bucket", bucket).param("symbols", symbols).param("from", Timestamp.from(Instant.now().minus(window)))
   .query(rs -> { out.computeIfAbsent(rs.getString("symbol"), k -> new ArrayList<>()).add(rs.getBigDecimal("p")); });
  return out;
 }

 /** Time-aligned snapshots of every asset (all assets captured together share a timestamp), oldest first. */
 public List<Snapshot> snapshots(Instant from, Instant to, int limit) {
  Map<Instant, Map<String, BigDecimal>> rows = new LinkedHashMap<>();
  jdbc.sql("""
    WITH ts AS (
      SELECT DISTINCT captured_at FROM price_snapshot
      WHERE captured_at >= COALESCE(CAST(:from AS timestamptz), '-infinity')
        AND captured_at <= COALESCE(CAST(:to AS timestamptz), 'infinity')
      ORDER BY captured_at DESC LIMIT :limit)
    SELECT p.captured_at, p.symbol, p.price_usd FROM price_snapshot p JOIN ts USING (captured_at)
    ORDER BY p.captured_at, p.symbol""")
   .param("from", from == null ? null : Timestamp.from(from)).param("to", to == null ? null : Timestamp.from(to)).param("limit", limit)
   .query(rs -> { rows.computeIfAbsent(rs.getTimestamp("captured_at").toInstant(), k -> new TreeMap<>()).put(rs.getString("symbol"), rs.getBigDecimal("price_usd")); });
  List<Snapshot> out = new ArrayList<>();
  rows.forEach((at, prices) -> out.add(new Snapshot(at, prices)));
  return out;
 }

 public List<Event> events(String symbol, Instant from, Instant to, int limit) {
  return jdbc.sql("""
    SELECT id, symbol, shock_pct, headline, source, occurred_at FROM market_event
    WHERE (CAST(:symbol AS text) IS NULL OR symbol = :symbol)
      AND occurred_at >= COALESCE(CAST(:from AS timestamptz), '-infinity')
      AND occurred_at <= COALESCE(CAST(:to AS timestamptz), 'infinity')
    ORDER BY occurred_at DESC LIMIT :limit""")
   .param("symbol", symbol).param("from", from == null ? null : Timestamp.from(from)).param("to", to == null ? null : Timestamp.from(to)).param("limit", limit)
   .query((rs, i) -> new Event(rs.getObject("id", UUID.class), rs.getString("symbol"), rs.getBigDecimal("shock_pct"),
    rs.getString("headline"), rs.getString("source"), rs.getTimestamp("occurred_at").toInstant()))
   .list();
 }
}
