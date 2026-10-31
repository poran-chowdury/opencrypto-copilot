package dev.opencrypto.apigateway;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Validates OIDC JWT tokens on protected routes, strips spoofed identity headers,
 * and injects verified caller identity (X-Auth-User, X-Auth-Tier, X-Demo-User).
 * Part of Step 5 (docs/ai/agentic-system.md).
 */
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {
 private static final List<String> PUBLIC_PREFIXES = List.of(
  "/api/v1/auth/",
  "/api/v1/prices/",
  "/api/v1/assets/",
  "/api/v1/market/",
  "/api/v1/system/",
  "/actuator/"
 );

 private final JwtTokenValidator tokenValidator;
 private final boolean authEnabled;

 public JwtAuthFilter(JwtTokenValidator tokenValidator, @Value("${auth.enabled:true}") boolean authEnabled) {
  this.tokenValidator = tokenValidator;
  this.authEnabled = authEnabled;
 }

 @Override
 public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
  ServerHttpRequest request = exchange.getRequest();
  String path = request.getPath().value();

  // Allow OPTIONS (CORS preflight) and public endpoints
  if (request.getMethod() == HttpMethod.OPTIONS || isPublic(path)) {
   // Strip spoofed caller headers from untrusted clients on public routes too
   ServerWebExchange cleaned = exchange.mutate()
    .request(r -> r.headers(h -> {
     h.remove("X-Auth-User");
     h.remove("X-Auth-Tier");
    }))
    .build();
   return chain.filter(cleaned);
  }

  // If auth is explicitly disabled (e.g. test suites), bypass token validation
  if (!authEnabled) {
   return chain.filter(exchange);
  }

  String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
  if (authHeader == null || !authHeader.startsWith("Bearer ")) {
   return reject(exchange, "Missing or invalid Bearer token");
  }

  String token = authHeader.substring(7).trim();
  if (token.isEmpty()) {
   return reject(exchange, "Empty Bearer token");
  }

  return tokenValidator.validate(token)
   .flatMap(user -> {
    String primaryRole = user.roles().isEmpty() ? user.tier() : user.roles().get(0);
    ServerWebExchange mutated = exchange.mutate()
     .request(r -> r.headers(h -> {
      // Strip untrusted client headers to prevent spoofing
      h.remove("X-Demo-User");
      h.remove("X-Demo-Role");
      h.remove("X-Auth-User");
      h.remove("X-Auth-Tier");
      // Inject verified caller context
      h.set("X-Auth-User", user.userId());
      h.set("X-Auth-Tier", user.tier());
      h.set("X-Demo-User", user.userId());
      h.set("X-Demo-Role", primaryRole);
     }))
     .build();
    return chain.filter(mutated);
   })
   .onErrorResume(err -> reject(exchange, "Authentication failed: " + err.getMessage()));
 }

 private boolean isPublic(String path) {
  for (String prefix : PUBLIC_PREFIXES) {
   if (path.startsWith(prefix) || path.equals(prefix.substring(0, prefix.length() - 1))) {
    return true;
   }
  }
  return false;
 }

 private Mono<Void> reject(ServerWebExchange exchange, String detail) {
  var response = exchange.getResponse();
  response.setStatusCode(HttpStatus.UNAUTHORIZED);
  response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
  String correlationId = exchange.getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER);
  String body = """
   {"type":"https://opencrypto.dev/problems/unauthorized","title":"Unauthorized","status":401,\
   "detail":"%s","code":"UNAUTHORIZED","correlationId":"%s"}"""
   .formatted(detail.replace("\"", "\\\""), correlationId == null ? "" : correlationId);
  return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
 }

 /** Run after CorrelationIdFilter (HIGHEST_PRECEDENCE) and before RateLimitFilter (HIGHEST_PRECEDENCE + 10). */
 @Override
 public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 5; }
}

