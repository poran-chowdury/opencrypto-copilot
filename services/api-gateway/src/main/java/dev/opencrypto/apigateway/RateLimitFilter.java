package dev.opencrypto.apigateway;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global rate limiting at the edge, before any service does work.
 *
 * Algorithm: token bucket per client, stored in Redis and updated by an atomic Lua script
 * (Spring Cloud Gateway's {@link RedisRateLimiter}), so the limit holds across every gateway
 * instance. Each client gets {@code burst} tokens that refill at {@code replenish} per second;
 * one request costs one token.
 *
 * Policies follow what a request costs, not its HTTP verb: reads (incl. read-only POSTs such as
 * knowledge search) are generous; writes (quotes, swaps, deposits, simulation controls) are strict
 * because they change state; AI chat/tool calls get their own budget because they will call a
 * paid LLM. The client key is the demo user header when
 * present, otherwise the client IP. Rejections return RFC 7807 JSON with Retry-After, plus the
 * standard X-RateLimit-* headers on every response so clients can back off before hitting the wall.
 * If Redis is down the limiter fails open (requests are allowed and an error is logged).
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {
 static final String READ = "read", WRITE = "write", AI = "ai";
 /** POST endpoints that only read data; limited like GETs. */
 private static final java.util.Set<String> READ_ONLY_POSTS = java.util.Set.of("/api/v1/ai/knowledge/search");
 private static final Pattern USER = Pattern.compile("[A-Za-z0-9._-]{1,40}");

 private final RedisRateLimiter limiter;
 private final MeterRegistry meters;

 public RateLimitFilter(RedisRateLimiter limiter, MeterRegistry meters) {
  this.limiter = limiter;
  this.meters = meters;
 }

 @Override
 public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
  ServerHttpRequest request = exchange.getRequest();
  String path = request.getPath().value();
  if (!path.startsWith("/api/")) return chain.filter(exchange);
  String policy = policyFor(request);
  String key = clientKey(request);
  return limiter.isAllowed(policy, key).flatMap(response -> {
   response.getHeaders().forEach((name, value) -> exchange.getResponse().getHeaders().add(name, value));
   if (response.isAllowed()) return chain.filter(exchange);
   meters.counter("gateway.ratelimit.rejected", "policy", policy).increment();
   return reject(exchange, policy);
  });
 }

 static String policyFor(ServerHttpRequest r) {
  String path = r.getPath().value();
  if (path.startsWith("/api/v1/ai/chat") || path.startsWith("/api/v1/ai/tools/")) return AI;
  if (!isWrite(r) || READ_ONLY_POSTS.contains(path)) return READ;
  return WRITE;
 }

 static boolean isWrite(ServerHttpRequest r) {
  return r.getMethod() != HttpMethod.GET && r.getMethod() != HttpMethod.HEAD && r.getMethod() != HttpMethod.OPTIONS;
 }

 static String clientKey(ServerHttpRequest r) {
  String user = r.getHeaders().getFirst("X-Auth-User");
  if (user == null || user.isBlank()) {
   user = r.getHeaders().getFirst("X-Demo-User");
  }
  if (user != null && USER.matcher(user).matches()) return "user:" + user;
  String forwarded = r.getHeaders().getFirst("X-Forwarded-For");
  if (forwarded != null && !forwarded.isBlank()) return "ip:" + forwarded.split(",")[0].trim();
  return "ip:" + (r.getRemoteAddress() == null ? "unknown" : r.getRemoteAddress().getAddress().getHostAddress());
 }

 private Mono<Void> reject(ServerWebExchange exchange, String policy) {
  var response = exchange.getResponse();
  response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
  response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
  response.getHeaders().set("Retry-After", "1");
  String correlationId = exchange.getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER);
  String body = """
   {"type":"https://opencrypto.dev/problems/rate-limited","title":"Too Many Requests","status":429,\
   "detail":"Rate limit exceeded for %s requests; retry after 1 second","code":"RATE_LIMITED","correlationId":"%s"}"""
   .formatted(policy, correlationId == null ? "" : correlationId);
  return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
 }

 /** After the correlation id filter, before routing. */
 @Override
 public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 10; }

 /**
  * {@code tokens} = cost of one request. Rates below 1 request/second are expressed by making a
  * request cost more than the per-second refill (e.g. replenish 1, cost 60 = one request a minute).
  */
 static Map<String, RedisRateLimiter.Config> policies(int readRate, int readBurst, int readTokens, int writeRate, int writeBurst, int writeTokens,
   int aiRate, int aiBurst) {
  return Map.of(
   AI, new RedisRateLimiter.Config().setReplenishRate(aiRate).setBurstCapacity(aiBurst).setRequestedTokens(1),
   READ, new RedisRateLimiter.Config().setReplenishRate(readRate).setBurstCapacity(readBurst).setRequestedTokens(readTokens),
   WRITE, new RedisRateLimiter.Config().setReplenishRate(writeRate).setBurstCapacity(writeBurst).setRequestedTokens(writeTokens));
 }
}
