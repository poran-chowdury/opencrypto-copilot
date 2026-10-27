package dev.opencrypto.apigateway;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Routes are code (not YAML) so they are type-checked and easy to read in one place. The gateway
 * only exposes {@code /api/v1/**}; service-internal and actuator endpoints stay private, except a
 * read-only health route per service for the status page.
 */
@Configuration
public class GatewayConfig {
 @Bean
 RouteLocator routes(RouteLocatorBuilder builder,
   @Value("${services.identity-url}") String identity, @Value("${services.asset-url}") String asset,
   @Value("${services.wallet-url}") String wallet, @Value("${services.transaction-url}") String transaction,
   @Value("${services.swap-url}") String swap, @Value("${services.ai-url}") String ai) {
  Map<String, String> health = Map.of("identity", identity, "asset", asset, "wallet", wallet, "transaction", transaction, "swap", swap, "ai", ai);
  RouteLocatorBuilder.Builder r = builder.routes()
   .route("identity", p -> p.path("/api/v1/auth/**").uri(identity))
   .route("assets", p -> p.path("/api/v1/assets/**", "/api/v1/prices/**", "/api/v1/market/**").uri(asset))
   .route("wallet", p -> p.path("/api/v1/wallet/**", "/api/v1/portfolio/**").uri(wallet))
   .route("transactions", p -> p.path("/api/v1/transactions/**").uri(transaction))
   .route("swaps", p -> p.path("/api/v1/swaps/**").uri(swap))
   .route("ai", p -> p.path("/api/v1/ai/**").uri(ai));
  health.forEach((name, uri) -> r.route("health-" + name, p -> p.path("/api/v1/system/" + name + "/health").and().method("GET")
   .filters(f -> f.setPath("/actuator/health")).uri(uri)));
  return r.build();
 }

 /** Token-bucket limits per policy; see {@link RateLimitFilter}. */
 @Bean
 RedisRateLimiter redisRateLimiter(@Value("${ratelimit.read.replenish-per-second:30}") int readRate, @Value("${ratelimit.read.burst:60}") int readBurst,
   @Value("${ratelimit.read.tokens-per-request:1}") int readTokens,
   @Value("${ratelimit.write.replenish-per-second:5}") int writeRate, @Value("${ratelimit.write.burst:10}") int writeBurst,
   @Value("${ratelimit.write.tokens-per-request:1}") int writeTokens,
   @Value("${ratelimit.ai.replenish-per-second:3}") int aiRate, @Value("${ratelimit.ai.burst:20}") int aiBurst) {
  RedisRateLimiter limiter = new RedisRateLimiter(readRate, readBurst);
  limiter.getConfig().putAll(RateLimitFilter.policies(readRate, readBurst, readTokens, writeRate, writeBurst, writeTokens, aiRate, aiBurst));
  return limiter;
 }
}
