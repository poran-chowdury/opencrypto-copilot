package dev.opencrypto.apigateway;

import static org.assertj.core.api.Assertions.*;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

class RateLimitKeyTest {
 @Test void userHeaderWins() {
  var r = MockServerHttpRequest.get("/api/v1/assets").header("X-Demo-User", "alice").remoteAddress(new InetSocketAddress("10.0.0.1", 1)).build();
  assertThat(RateLimitFilter.clientKey(r)).isEqualTo("user:alice");
 }

 @Test void malformedUserFallsBackToIp() {
  var r = MockServerHttpRequest.get("/api/v1/assets").header("X-Demo-User", "bad user!*").remoteAddress(new InetSocketAddress("10.0.0.1", 1)).build();
  assertThat(RateLimitFilter.clientKey(r)).isEqualTo("ip:10.0.0.1");
 }

 @Test void forwardedForUsesFirstHop() {
  var r = MockServerHttpRequest.get("/api/v1/assets").header("X-Forwarded-For", "203.0.113.9, 10.0.0.2").build();
  assertThat(RateLimitFilter.clientKey(r)).isEqualTo("ip:203.0.113.9");
 }

 @Test void policyFollowsCostNotVerb() {
  assertThat(RateLimitFilter.policyFor(MockServerHttpRequest.get("/api/v1/assets").build())).isEqualTo("read");
  assertThat(RateLimitFilter.policyFor(MockServerHttpRequest.post("/api/v1/ai/knowledge/search").build())).isEqualTo("read");
  assertThat(RateLimitFilter.policyFor(MockServerHttpRequest.post("/api/v1/ai/chat").build())).isEqualTo("ai");
  assertThat(RateLimitFilter.policyFor(MockServerHttpRequest.post("/api/v1/swaps").build())).isEqualTo("write");
 }

 @Test void onlySafeMethodsAreReads() {
  assertThat(RateLimitFilter.isWrite(MockServerHttpRequest.get("/x").build())).isFalse();
  assertThat(RateLimitFilter.isWrite(MockServerHttpRequest.post("/x").build())).isTrue();
  assertThat(RateLimitFilter.isWrite(MockServerHttpRequest.delete("/x").build())).isTrue();
 }
}
