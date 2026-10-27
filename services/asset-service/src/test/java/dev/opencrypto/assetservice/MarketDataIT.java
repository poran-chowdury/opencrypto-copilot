package dev.opencrypto.assetservice;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import dev.opencrypto.assetservice.market.coingecko.CoinGeckoSyncJob;

/** Real Postgres + Redis, CoinGecko replaced by WireMock so failures can be scripted. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
 "market.provider=coingecko", "market.coingecko.initial-delay=PT1H", "market.coingecko.sync-interval=PT1H",
 "resilience4j.retry.instances.coingecko.wait-duration=10ms"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MarketDataIT {
 @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
 @Container static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
 static WireMockServer coingecko = new WireMockServer(WireMockConfiguration.options().dynamicPort());

 static final String MARKETS = """
  [{"id":"bitcoin","symbol":"btc","name":"Bitcoin","image":"https://img/btc.png","current_price":80000,"market_cap":1600000000000,
    "market_cap_rank":1,"total_volume":30000000000,"price_change_percentage_24h":1.5,"last_updated":"2026-10-25T08:00:00Z",
    "sparkline_in_7d":{"price":[79000,79500,80000]}},
   {"id":"ethereum","symbol":"eth","name":"Ethereum","image":"https://img/eth.png","current_price":2300,"market_cap":280000000000,
    "market_cap_rank":2,"total_volume":12000000000,"price_change_percentage_24h":-2.0,"last_updated":"2026-10-25T08:00:00Z",
    "sparkline_in_7d":{"price":[2400,2350,2300]}}]""";

 @BeforeAll static void startWireMock() { coingecko.start(); }
 @AfterAll static void stopWireMock() { coingecko.stop(); }

 @DynamicPropertySource
 static void props(DynamicPropertyRegistry r) {
  r.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=asset");
  r.add("spring.datasource.username", postgres::getUsername);
  r.add("spring.datasource.password", postgres::getPassword);
  r.add("spring.data.redis.host", redis::getHost);
  r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
  r.add("market.coingecko.base-url", coingecko::baseUrl);
 }

 @Autowired CoinGeckoSyncJob sync;
 @Autowired TestRestTemplate http;
 @Autowired JdbcClient jdbc;
 @Autowired StringRedisTemplate redisTemplate;
 @Autowired CircuitBreakerRegistry breakers;

 @BeforeEach void reset() {
  coingecko.resetAll();
  breakers.circuitBreaker("coingecko").transitionToClosedState();
 }

 @Test @Order(1)
 void syncStoresPricesHistoryAndCache() {
  coingecko.stubFor(get(urlPathEqualTo("/coins/markets")).willReturn(okJson(MARKETS)));
  sync.syncOnce();

  Map<?, ?> page = http.getForObject("/api/v1/assets?size=2&sort=marketCap,desc", Map.class);
  assertThat(page.get("totalItems")).isEqualTo(13);
  var items = (java.util.List<Map<String, Object>>) page.get("items");
  assertThat(items).extracting(i -> i.get("symbol")).containsExactly("BTC", "ETH");
  assertThat(((Number) items.getFirst().get("priceUsd")).doubleValue()).isEqualTo(80000.0);

  assertThat(jdbc.sql("SELECT count(*) FROM price_snapshot WHERE symbol = 'BTC'").query(Long.class).single()).isGreaterThanOrEqualTo(4); // live + 3 sparkline points
  assertThat(redisTemplate.opsForHash().size("asset:prices:latest")).isGreaterThanOrEqualTo(3);
  assertThat(jdbc.sql("SELECT status FROM sync_run ORDER BY id DESC LIMIT 1").query(String.class).single()).isEqualTo("OK");
  coingecko.verify(getRequestedFor(urlPathEqualTo("/coins/markets")).withQueryParam("sparkline", equalTo("true")));
 }

 @Test @Order(2)
 void pricesFallBackToPostgresWhenCacheIsEmpty() {
  redisTemplate.delete("asset:prices:latest");
  Map<?, ?> body = http.getForObject("/api/v1/prices?symbols=USD", Map.class);
  assertThat(((Map<?, ?>) body.get("prices")).keySet()).extracting(Object::toString).contains("USD");
 }

 @Test @Order(3)
 void rateLimitedUpstreamIsRetriedThenCircuitOpens() {
  coingecko.stubFor(get(urlPathEqualTo("/coins/markets")).willReturn(aResponse().withStatus(429)));
  for (int i = 0; i < 3; i++) sync.syncOnce();
  coingecko.verify(moreThanOrExactly(3), getRequestedFor(urlPathEqualTo("/coins/markets"))); // retries happened
  assertThat(breakers.circuitBreaker("coingecko").getState()).isEqualTo(CircuitBreaker.State.OPEN);
  int callsBefore = coingecko.getAllServeEvents().size();
  sync.syncOnce(); // short-circuited: CoinGecko is not called while the breaker is open
  assertThat(coingecko.getAllServeEvents()).hasSize(callsBefore);
  assertThat(jdbc.sql("SELECT error FROM sync_run ORDER BY id DESC LIMIT 1").query(String.class).single()).contains("Circuit breaker open");
 }

 @Test @Order(4)
 void sortingIsWhitelistedAndValidated() {
  var bad = http.getForEntity("/api/v1/assets?sort=price;DROP TABLE asset", Map.class);
  assertThat(bad.getStatusCode().value()).isEqualTo(400);
  assertThat(bad.getBody().get("code")).isEqualTo("INVALID_SORT");
  var tooBig = http.getForEntity("/api/v1/assets?size=500", Map.class);
  assertThat(tooBig.getStatusCode().value()).isEqualTo(400);
  Map<?, ?> stable = http.getForObject("/api/v1/assets?kind=STABLECOIN&q=us", Map.class);
  assertThat((java.util.List<?>) stable.get("items")).hasSize(2);
 }
}
