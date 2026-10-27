package dev.opencrypto.assetservice.market;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import dev.opencrypto.assetservice.catalog.AssetRepository;
import dev.opencrypto.assetservice.catalog.CatalogAsset;
import dev.opencrypto.assetservice.market.PriceCache.CachedPrice;

/** Latest prices: Redis first, Postgres on a miss or Redis failure (cache-aside read). */
@Service
public class PriceService {
 private final PriceCache cache;
 private final AssetRepository assets;
 private final MarketState state;

 public PriceService(PriceCache cache, AssetRepository assets, MarketState state) {
  this.cache = cache;
  this.assets = assets;
  this.state = state;
 }

 public Map<String, CachedPrice> latest() {
  return cache.getAll().orElseGet(this::refreshCache);
 }

 /** Reload from Postgres into Redis; returns what was loaded. */
 public Map<String, CachedPrice> refreshCache() {
  Map<String, CachedPrice> fromDb = new TreeMap<>();
  for (CatalogAsset a : assets.findAllActive())
   if (a.priceUsd() != null) fromDb.put(a.symbol(), new CachedPrice(a.priceUsd(), a.change24hPct(), a.priceUpdatedAt(), a.priceSource()));
  if (!fromDb.isEmpty()) cache.putAll(fromDb);
  return fromDb;
 }

 public long ageSeconds(Instant updatedAt) {
  return updatedAt == null ? Long.MAX_VALUE : Math.max(0, Duration.between(updatedAt, Instant.now()).toSeconds());
 }

 public boolean isStale(String kind, Instant updatedAt) {
  return !"FIAT".equals(kind) && ageSeconds(updatedAt) > state.staleAfterSeconds();
 }
}
