package dev.opencrypto.apigateway;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Token bucket in real Redis: the burst is honoured, the excess gets RFC 7807 429s, users are isolated. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
 // burst of 3 requests, refilling one request per minute: deterministic regardless of upstream latency
 "ratelimit.write.replenish-per-second=1", "ratelimit.write.burst=180", "ratelimit.write.tokens-per-request=60",
 "services.swap-url=http://localhost:1"})   // nothing listens: allowed requests fail upstream, which is fine here
class RateLimitIT {
 @Container static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

 @DynamicPropertySource
 static void props(DynamicPropertyRegistry r) {
  r.add("spring.data.redis.host", redis::getHost);
  r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
 }

 @Autowired WebTestClient web;

 @Test
 void writeBurstIsLimitedPerUser() {
  List<Integer> statuses = new ArrayList<>();
  for (int i = 0; i < 6; i++) statuses.add(post("mallory"));
  assertThat(statuses.stream().filter(s -> s == 429).count()).isEqualTo(3);   // burst of 3 allowed, then limited
  assertThat(post("alice")).isNotEqualTo(429);                                  // other users have their own bucket

  web.post().uri("/api/v1/swaps/quotes").header("X-Demo-User", "mallory").bodyValue("{}")
   .header("Content-Type", "application/json").exchange()
   .expectStatus().isEqualTo(429)
   .expectHeader().valueEquals("Retry-After", "1")
   .expectHeader().exists("X-Correlation-Id")
   .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED");
 }

 private int post(String user) {
  return web.post().uri("/api/v1/swaps/quotes").header("X-Demo-User", user).header("Content-Type", "application/json")
   .bodyValue("{}").exchange().returnResult(String.class).getStatus().value();
 }
}
