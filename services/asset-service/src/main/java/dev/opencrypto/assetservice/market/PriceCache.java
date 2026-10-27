package dev.opencrypto.assetservice.market;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Latest prices in a Redis hash, the hot read path for every service that values a balance.
 *
 * Write-through: ingestion refreshes the hash after each committed sync. Redis is a cache, never
 * the source of truth; when it is empty or down, callers fall back to Postgres. A TTL means a
 * stopped sync can't leave stale prices in Redis forever.
 */
@Component
public class PriceCache {
 public record CachedPrice(BigDecimal priceUsd, BigDecimal change24hPct, Instant updatedAt, String source) {}

 static final String KEY = "asset:prices:latest";
 private static final Duration TTL = Duration.ofMinutes(10);
 private static final Logger log = LoggerFactory.getLogger(PriceCache.class);

 private final StringRedisTemplate redis;
 private final ObjectMapper json;
 private final Counter hits, misses, errors;

 public PriceCache(StringRedisTemplate redis, ObjectMapper json, MeterRegistry meters) {
  this.redis = redis;
  this.json = json;
  this.hits = Counter.builder("price.cache.requests").tag("result", "hit").register(meters);
  this.misses = Counter.builder("price.cache.requests").tag("result", "miss").register(meters);
  this.errors = Counter.builder("price.cache.requests").tag("result", "error").register(meters);
 }

 public Optional<Map<String, CachedPrice>> getAll() {
  try {
   Map<Object, Object> raw = redis.opsForHash().entries(KEY);
   if (raw.isEmpty()) { misses.increment(); return Optional.empty(); }
   Map<String, CachedPrice> out = new TreeMap<>();
   for (var e : raw.entrySet()) out.put(e.getKey().toString(), json.readValue(e.getValue().toString(), CachedPrice.class));
   hits.increment();
   return Optional.of(out);
  } catch (Exception e) {
   errors.increment();
   log.warn("Price cache read failed, falling back to Postgres: {}", e.getMessage());
   return Optional.empty();
  }
 }

 public void putAll(Map<String, CachedPrice> prices) {
  try {
   Map<String, String> values = new HashMap<>();
   for (var e : prices.entrySet()) values.put(e.getKey(), json.writeValueAsString(e.getValue()));
   redis.opsForHash().putAll(KEY, values);
   redis.expire(KEY, TTL);
  } catch (Exception e) {
   errors.increment();
   log.warn("Price cache write failed (prices still served from Postgres): {}", e.getMessage());
  }
 }
}
