package dev.opencrypto.walletservice.pricing;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls asset-service for prices and asset metadata. Built from Spring's RestClient.Builder so the
 * correlation id is forwarded; protected by a timeout, retry and circuit breaker (instance
 * {@code assetService}) so a slow asset-service can't exhaust wallet threads.
 */
@Component
public class AssetClient {
 @JsonIgnoreProperties(ignoreUnknown = true)
 public record Price(BigDecimal priceUsd, BigDecimal change24hPct, Instant updatedAt, long ageSeconds, boolean stale, String source) {}

 @JsonIgnoreProperties(ignoreUnknown = true)
 public record AssetInfo(String symbol, String name, String kind, int decimals, BigDecimal priceUsd, boolean stale) {}

 @JsonIgnoreProperties(ignoreUnknown = true)
 public record Snapshot(Instant at, Map<String, BigDecimal> prices) {}

 @JsonIgnoreProperties(ignoreUnknown = true)
 public record PricesBody(Map<String, Price> prices) {}

 @JsonIgnoreProperties(ignoreUnknown = true)
 public record SnapshotsBody(List<Snapshot> snapshots) {}

 private final RestClient http;

 public AssetClient(RestClient.Builder builder, @Value("${services.asset-url}") String baseUrl) {
  JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
  factory.setReadTimeout(Duration.ofSeconds(3));
  this.http = builder.baseUrl(baseUrl).requestFactory(factory).build();
 }

 @Retry(name = "assetService")
 @CircuitBreaker(name = "assetService")
 public Map<String, Price> prices() {
  PricesBody body = http.get().uri("/api/v1/prices").retrieve().body(PricesBody.class);
  return body == null || body.prices() == null ? Map.of() : body.prices();
 }

 @Retry(name = "assetService")
 @CircuitBreaker(name = "assetService")
 public AssetInfo asset(String symbol) {
  return http.get().uri("/api/v1/assets/{s}", symbol).retrieve().body(AssetInfo.class);
 }

 @Retry(name = "assetService")
 @CircuitBreaker(name = "assetService")
 public List<Snapshot> snapshots(int limit, Instant from, Instant to) {
  SnapshotsBody body = http.get().uri(b -> {
   b.path("/api/v1/market/snapshots").queryParam("limit", limit);
   if (from != null) b.queryParam("from", from.toString());
   if (to != null) b.queryParam("to", to.toString());
   return b.build();
  }).retrieve().body(SnapshotsBody.class);
  return body == null || body.snapshots() == null ? List.of() : body.snapshots();
 }
}
