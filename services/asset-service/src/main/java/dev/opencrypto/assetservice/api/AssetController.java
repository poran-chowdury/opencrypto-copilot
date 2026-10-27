package dev.opencrypto.assetservice.api;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import dev.opencrypto.assetservice.catalog.AssetRepository;
import dev.opencrypto.assetservice.catalog.CatalogAsset;
import dev.opencrypto.assetservice.market.HistoryRepository;
import dev.opencrypto.assetservice.market.MarketState;
import dev.opencrypto.assetservice.market.PriceService;
import dev.opencrypto.platform.web.ApiException;

@RestController
@RequestMapping("/api/v1/assets")
@Validated
public class AssetController {
 public record AssetView(String symbol, String name, String kind, int decimals, String imageUrl, BigDecimal priceUsd,
   BigDecimal change24hPct, String changeWindow, BigDecimal marketCapUsd, BigDecimal volume24hUsd, Integer marketCapRank,
   Instant updatedAt, Long ageSeconds, boolean stale, String source, List<BigDecimal> sparkline,
   String description, JsonNode networks) {}

 private static final Map<String, Duration> RANGES = Map.of("1h", Duration.ofHours(1), "24h", Duration.ofHours(24),
  "7d", Duration.ofDays(7), "30d", Duration.ofDays(30));

 private final AssetRepository assets;
 private final HistoryRepository history;
 private final PriceService prices;
 private final MarketState state;
 private final ObjectMapper json;

 public AssetController(AssetRepository assets, HistoryRepository history, PriceService prices, MarketState state, ObjectMapper json) {
  this.assets = assets;
  this.history = history;
  this.prices = prices;
  this.state = state;
  this.json = json;
 }

 /** e.g. /api/v1/assets?page=0&size=20&sort=marketCap,desc&q=bit&kind=CRYPTO&sparkline=true */
 @GetMapping
 public PageResponse<AssetView> list(@RequestParam(defaultValue = "0") @Min(0) int page,
   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
   @RequestParam(defaultValue = "marketCap,desc") String sort,
   @RequestParam(required = false) String q, @RequestParam(required = false) String kind,
   @RequestParam(defaultValue = "false") boolean sparkline) {
  state.requireFeed();
  String[] parts = sort.split(",");
  boolean asc = parts.length > 1 && parts[1].equalsIgnoreCase("asc");
  AssetRepository.PageQuery query = new AssetRepository.PageQuery(page, size, parts[0], asc, q, kind);
  List<CatalogAsset> rows = assets.page(query);
  Map<String, List<BigDecimal>> lines = sparkline
   ? history.sparklines(rows.stream().map(CatalogAsset::symbol).toList(), sparklineWindow(), 40) : Map.of();
  List<AssetView> items = rows.stream().map(a -> view(a, lines.get(a.symbol()), false)).toList();
  return PageResponse.of(items, page, size, assets.count(query), parts[0] + "," + (asc ? "asc" : "desc"));
 }

 @GetMapping("/{symbol}")
 public AssetView detail(@PathVariable String symbol) {
  state.requireFeed();
  CatalogAsset a = assets.require(symbol);
  return view(a, history.sparklines(List.of(a.symbol()), sparklineWindow(), 40).get(a.symbol()), true);
 }

 /** Down-sampled history. Use range=1h|24h|7d|30d, or explicit ISO-8601 from/to. */
 @GetMapping("/{symbol}/history")
 public Map<String, Object> history(@PathVariable String symbol, @RequestParam(required = false) String range,
   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
   @RequestParam(defaultValue = "240") @Min(2) @Max(2000) int points) {
  state.requireFeed();
  CatalogAsset a = assets.require(symbol);
  Instant end = to != null ? to : Instant.now();
  Instant start;
  if (from != null) start = from;
  else {
   Duration d = RANGES.get(range == null ? "24h" : range);
   if (d == null) throw ApiException.badRequest("INVALID_RANGE", "range must be one of " + new TreeSet<>(RANGES.keySet()));
   start = end.minus(d);
  }
  if (!start.isBefore(end)) throw ApiException.badRequest("INVALID_RANGE", "from must be before to");
  List<HistoryRepository.Point> series = history.history(a.symbol(), start, end, points);
  return Map.of("symbol", a.symbol(), "from", start, "to", end, "points", series, "count", series.size(), "provider", state.provider());
 }

 private Duration sparklineWindow() {
  return state.simulated() ? Duration.ofHours(1) : Duration.ofDays(7);
 }

 AssetView view(CatalogAsset a, List<BigDecimal> sparkline, boolean withDetail) {
  JsonNode networks = null;
  if (withDetail) {
   try { networks = json.readTree(a.networks()); } catch (Exception e) { networks = json.createArrayNode(); }
  }
  Long age = a.priceUpdatedAt() == null ? null : prices.ageSeconds(a.priceUpdatedAt());
  return new AssetView(a.symbol(), a.name(), a.kind(), a.decimals(), a.imageUrl(), a.priceUsd(), a.change24hPct(),
   state.simulated() ? "100 ticks" : "24h", a.marketCapUsd(), a.volume24hUsd(), a.marketCapRank(), a.priceUpdatedAt(), age,
   prices.isStale(a.kind(), a.priceUpdatedAt()), a.priceSource(), sparkline == null ? List.of() : sparkline,
   withDetail ? a.description() : null, networks);
 }
}
