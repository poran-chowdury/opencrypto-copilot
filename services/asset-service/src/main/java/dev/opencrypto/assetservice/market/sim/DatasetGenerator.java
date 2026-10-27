package dev.opencrypto.assetservice.market.sim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import dev.opencrypto.platform.web.ApiException;

/**
 * Long, reproducible price histories for offline analysis and ML, independent of the live market.
 * Same parameters => byte-identical output. The hidden volatility regime can be included as a
 * ground-truth label for evaluating risk models.
 */
public final class DatasetGenerator {
 public static final Instant ANCHOR = Instant.parse("2025-01-01T00:00:00Z");
 public static final int MAX_ROWS = 200_000;

 public record Row(Instant at, Map<String, Double> prices, String regime, PriceModel.Jump event) {}

 private DatasetGenerator() {}

 public static List<Row> generate(List<PriceModel.SimAsset> assets, long seed, int days, int intervalMinutes, double eventsPerDay) {
  if (days < 1 || days > 1095) throw ApiException.badRequest("INVALID_DATASET", "days must be between 1 and 1095");
  if (intervalMinutes < 1 || intervalMinutes > 1440) throw ApiException.badRequest("INVALID_DATASET", "intervalMinutes must be between 1 and 1440");
  if (eventsPerDay < 0 || eventsPerDay > 50) throw ApiException.badRequest("INVALID_DATASET", "eventsPerDay must be between 0 and 50");
  long rows = (long) days * 24 * 60 / intervalMinutes;
  if (rows > MAX_ROWS) throw ApiException.badRequest("INVALID_DATASET", "Too many rows (" + rows + "); max " + MAX_ROWS + ". Use fewer days or a larger interval.");

  PriceModel model = new PriceModel(assets, new Random(seed), 0.7);
  double eventProbability = Math.min(1, eventsPerDay * intervalMinutes / (24.0 * 60));
  List<Row> out = new ArrayList<>((int) rows + 1);
  out.add(new Row(ANCHOR, Map.copyOf(model.prices()), model.regime().name(), null));
  for (int i = 1; i <= rows; i++) {
   Optional<PriceModel.Jump> jump = model.step(intervalMinutes, 1.0, eventProbability);
   out.add(new Row(ANCHOR.plus(Duration.ofMinutes((long) i * intervalMinutes)), Map.copyOf(model.prices()), model.regime().name(), jump.orElse(null)));
  }
  return out;
 }

 public static String toCsv(List<PriceModel.SimAsset> assets, List<Row> rows, boolean includeRegime) {
  List<String> symbols = assets.stream().map(PriceModel.SimAsset::symbol).toList();
  StringBuilder sb = new StringBuilder("timestamp");
  symbols.forEach(s -> sb.append(',').append(s));
  if (includeRegime) sb.append(",regime");
  sb.append(",event_symbol,event_shock_pct,event_headline\n");
  for (Row r : rows) {
   sb.append(r.at());
   for (String s : symbols) sb.append(',').append(price(r.prices().get(s)).toPlainString());
   if (includeRegime) sb.append(',').append(r.regime());
   if (r.event() != null)
    sb.append(',').append(r.event().symbol()).append(',').append(BigDecimal.valueOf(r.event().shockPct()).setScale(2, RoundingMode.HALF_UP))
     .append(",\"").append(r.event().headline().replace("\"", "\"\"")).append('"');
   else sb.append(",,,");
   sb.append('\n');
  }
  return sb.toString();
 }

 public static BigDecimal price(double p) { return BigDecimal.valueOf(p).setScale(p < 10 ? 6 : 2, RoundingMode.HALF_UP); }
}
