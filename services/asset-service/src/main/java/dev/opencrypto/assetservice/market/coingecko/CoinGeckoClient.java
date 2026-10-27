package dev.opencrypto.assetservice.market.coingecko;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * CoinGecko REST client (https://docs.coingecko.com/v3.0.1/reference/coins-markets).
 *
 * Third-party APIs are slow, rate limited and occasionally down, so every call has:
 * a connect and read timeout, a retry with exponential backoff for transient failures
 * (timeouts, 5xx, 429), and a circuit breaker that stops calling for a while after repeated
 * failures. Resilience4j wires both from {@code resilience4j.*.instances.coingecko} in
 * application.yml. Internal headers (correlation id) are deliberately not sent to a third party.
 */
@Component
@ConditionalOnProperty(name = "market.provider", havingValue = "coingecko", matchIfMissing = true)
public class CoinGeckoClient {
 @JsonIgnoreProperties(ignoreUnknown = true)
 public record Market(String id, String symbol, String name, String image,
   @JsonProperty("current_price") BigDecimal currentPrice,
   @JsonProperty("market_cap") BigDecimal marketCap,
   @JsonProperty("market_cap_rank") Integer marketCapRank,
   @JsonProperty("total_volume") BigDecimal totalVolume,
   @JsonProperty("price_change_percentage_24h") BigDecimal priceChangePercentage24h,
   @JsonProperty("last_updated") Instant lastUpdated,
   @JsonProperty("sparkline_in_7d") Sparkline sparkline) {}

 @JsonIgnoreProperties(ignoreUnknown = true)
 public record Sparkline(List<BigDecimal> price) {}

 private final RestClient http;

 public CoinGeckoClient(@Value("${market.coingecko.base-url}") String baseUrl, @Value("${market.coingecko.api-key:}") String apiKey,
   @Value("${market.coingecko.connect-timeout:PT3S}") Duration connectTimeout, @Value("${market.coingecko.read-timeout:PT10S}") Duration readTimeout) {
  JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build());
  factory.setReadTimeout(readTimeout);
  RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
   .defaultHeader("Accept", "application/json").defaultHeader("User-Agent", "opencrypto-copilot/0.1");
  if (!apiKey.isBlank()) builder.defaultHeader("x-cg-demo-api-key", apiKey);
  this.http = builder.build();
 }

 /** One request for all assets: price, market cap, volume, 24h change and a 7-day hourly sparkline. */
 @Retry(name = "coingecko")
 @CircuitBreaker(name = "coingecko")
 public List<Market> markets(List<String> ids) {
  return http.get().uri(u -> u.path("/coins/markets")
    .queryParam("vs_currency", "usd")
    .queryParam("ids", String.join(",", ids))
    .queryParam("sparkline", "true")
    .queryParam("price_change_percentage", "24h")
    .queryParam("per_page", 250)
    .build())
   .retrieve().body(new ParameterizedTypeReference<>() {});
 }
}
