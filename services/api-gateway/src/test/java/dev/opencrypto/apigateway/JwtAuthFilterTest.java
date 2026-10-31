package dev.opencrypto.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import reactor.core.publisher.Mono;

class JwtAuthFilterTest {
 private RSAKey rsaJwk;
 private JwtTokenValidator validator;
 private JwtAuthFilter filter;

 @BeforeEach
 void setUp() throws Exception {
  rsaJwk = new RSAKeyGenerator(2048).keyID("test-key-1").generate();
  validator = new JwtTokenValidator(WebClient.builder(), "http://localhost:8081/unused");
  validator.setCachedJwks(new JWKSet(rsaJwk.toPublicJWK()));
  filter = new JwtAuthFilter(validator, true);
 }

 private String createToken(String subject, String tier, long validitySeconds) throws Exception {
  Instant now = Instant.now();
  JWTClaimsSet claims = new JWTClaimsSet.Builder()
   .issuer("http://localhost:8080/api/v1/auth")
   .subject(subject)
   .claim("tier", tier)
   .claim("roles", List.of("ROLE_USER"))
   .issueTime(Date.from(now))
   .expirationTime(Date.from(now.plusSeconds(validitySeconds)))
   .build();

  SignedJWT signedJwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaJwk.getKeyID()).build(), claims);
  signedJwt.sign(new RSASSASigner(rsaJwk.toRSAPrivateKey()));
  return signedJwt.serialize();
 }

 @Test
 void publicRoutePassesThroughWithoutAuth() {
  MockServerWebExchange exchange = MockServerWebExchange.from(
   MockServerHttpRequest.get("/api/v1/prices/BTC-USD").header("X-Auth-User", "spoofed").build()
  );
  AtomicReference<ServerWebExchange> filteredExchange = new AtomicReference<>();
  GatewayFilterChain chain = ex -> {
   filteredExchange.set(ex);
   return Mono.empty();
  };

  filter.filter(exchange, chain).block();

  assertThat(filteredExchange.get()).isNotNull();
  // Public route should strip spoofed X-Auth-User
  assertThat(filteredExchange.get().getRequest().getHeaders().getFirst("X-Auth-User")).isNull();
 }

 @Test
 void protectedRouteWithoutAuthReturns401() {
  MockServerWebExchange exchange = MockServerWebExchange.from(
   MockServerHttpRequest.get("/api/v1/portfolio").build()
  );
  GatewayFilterChain chain = ex -> Mono.empty();

  filter.filter(exchange, chain).block();

  assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
 }

 @Test
 void protectedRouteWithExpiredTokenReturns401() throws Exception {
  String expiredToken = createToken("alice", "PREMIUM", -10); // expired 10 seconds ago
  MockServerWebExchange exchange = MockServerWebExchange.from(
   MockServerHttpRequest.get("/api/v1/portfolio").header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken).build()
  );
  GatewayFilterChain chain = ex -> Mono.empty();

  filter.filter(exchange, chain).block();

  assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
 }

 @Test
 void protectedRouteWithValidTokenInjectsVerifiedHeadersAndStripsSpoofing() throws Exception {
  String token = createToken("alice", "PREMIUM", 3600);
  // Attacker attempts to spoof Bob in the headers
  MockServerWebExchange exchange = MockServerWebExchange.from(
   MockServerHttpRequest.get("/api/v1/portfolio")
    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
    .header("X-Demo-User", "bob")
    .header("X-Auth-User", "bob")
    .header("X-Auth-Tier", "PRIVILEGED")
    .build()
  );

  AtomicReference<ServerWebExchange> filteredExchange = new AtomicReference<>();
  GatewayFilterChain chain = ex -> {
   filteredExchange.set(ex);
   return Mono.empty();
  };

  filter.filter(exchange, chain).block();

  assertThat(filteredExchange.get()).isNotNull();
  var downstreamHeaders = filteredExchange.get().getRequest().getHeaders();
  // Spoofed headers are stripped and replaced with Alice's verified claims
  assertThat(downstreamHeaders.getFirst("X-Auth-User")).isEqualTo("alice");
  assertThat(downstreamHeaders.getFirst("X-Auth-Tier")).isEqualTo("PREMIUM");
  assertThat(downstreamHeaders.getFirst("X-Demo-User")).isEqualTo("alice");
  assertThat(downstreamHeaders.getFirst("X-Demo-Role")).isEqualTo("ROLE_USER");
 }
}

