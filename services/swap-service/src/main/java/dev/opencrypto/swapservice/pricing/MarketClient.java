package dev.opencrypto.swapservice.pricing;

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

/** Prices and asset precision from asset-service (timeout + retry + circuit breaker "assetService"). */
@Component
public class MarketClient {
 @JsonIgnoreProperties(ignoreUnknown = true)
 public record Price(BigDecimal priceUsd, Instant updatedAt, long ageSeconds, boolean stale) {}
 @JsonIgnoreProperties(ignoreUnknown = true)
 public record PricesBody(Map<String, Price> prices) {}
 @JsonIgnoreProperties(ignoreUnknown = true)
 public record AssetRow(String symbol, int decimals, String kind) {}
 @JsonIgnoreProperties(ignoreUnknown = true)
 public record AssetPage(List<AssetRow> items) {}

 private final RestClient http;
 private volatile Map<String, Integer> decimals = Map.of();
 private volatile Instant decimalsLoadedAt = Instant.EPOCH;

 public MarketClient(RestClient.Builder builder, @Value("${services.asset-url}") String baseUrl) {
  JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
  factory.setReadTimeout(Duration.ofSeconds(3));
  this.http = builder.baseUrl(baseUrl).requestFactory(factory).build();
 }

 @Retry(name = "assetService")
 @CircuitBreaker(name = "assetService")
 public Map<String, Price> prices(Collection<String> symbols) {
  PricesBody body = http.get().uri(b -> b.path("/api/v1/prices").queryParam("symbols", String.join(",", symbols)).build())
   .retrieve().body(PricesBody.class);
  return body == null || body.prices() == null ? Map.of() : body.prices();
 }

 /** Supported symbols and their decimal places; refreshed every 10 minutes (the catalogue rarely changes). */
 @Retry(name = "assetService")
 @CircuitBreaker(name = "assetService")
 public Map<String, Integer> decimals() {
  if (decimals.isEmpty() || decimalsLoadedAt.isBefore(Instant.now().minus(Duration.ofMinutes(10)))) {
   AssetPage page = http.get().uri("/api/v1/assets?size=100").retrieve().body(AssetPage.class);
   Map<String, Integer> d = new HashMap<>();
   if (page != null && page.items() != null) page.items().forEach(a -> d.put(a.symbol(), a.decimals()));
   decimals = Map.copyOf(d);
   decimalsLoadedAt = Instant.now();
  }
  return decimals;
 }
}
