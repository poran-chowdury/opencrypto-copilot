package dev.opencrypto.assetservice.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import dev.opencrypto.assetservice.catalog.AssetRepository;
import dev.opencrypto.assetservice.catalog.CatalogAsset;
import dev.opencrypto.assetservice.market.MarketState;
import dev.opencrypto.assetservice.market.PriceCache.CachedPrice;
import dev.opencrypto.assetservice.market.PriceService;

/**
 * Latest prices for valuation (wallet, swaps, copilot). Served from Redis; every price carries
 * its timestamp and a {@code stale} flag so callers can decide whether to trust it.
 */
@RestController
@RequestMapping("/api/v1/prices")
public class PriceController {
 public record PriceView(BigDecimal priceUsd, BigDecimal change24hPct, Instant updatedAt, long ageSeconds, boolean stale, String source) {}

 private final PriceService prices;
 private final AssetRepository assets;
 private final MarketState state;

 public PriceController(PriceService prices, AssetRepository assets, MarketState state) {
  this.prices = prices;
  this.assets = assets;
  this.state = state;
 }

 @GetMapping
 public Map<String, Object> latest(@RequestParam(required = false) List<String> symbols) {
  state.requireFeed();
  Map<String, String> kinds = new HashMap<>();
  for (CatalogAsset a : assets.findAllActive()) kinds.put(a.symbol(), a.kind());
  Set<String> wanted = symbols == null || symbols.isEmpty() ? null : new HashSet<>(symbols.stream().map(s -> s.toUpperCase(Locale.ROOT)).toList());
  Map<String, PriceView> out = new TreeMap<>();
  for (Map.Entry<String, CachedPrice> e : prices.latest().entrySet()) {
   if (wanted != null && !wanted.contains(e.getKey())) continue;
   CachedPrice p = e.getValue();
   out.put(e.getKey(), new PriceView(p.priceUsd(), p.change24hPct(), p.updatedAt(), prices.ageSeconds(p.updatedAt()),
    prices.isStale(kinds.getOrDefault(e.getKey(), "CRYPTO"), p.updatedAt()), p.source()));
  }
  return Map.of("provider", state.provider(), "asOf", Instant.now(), "prices", out);
 }
}
