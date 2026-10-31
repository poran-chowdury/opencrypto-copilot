package dev.opencrypto.apigateway;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import reactor.core.publisher.Mono;

/**
 * Validates RS256 JWT tokens issued by identity-service against its published JWKS.
 * Non-blocking, caches keys in memory, and validates signatures.
 * Part of Step 5 (docs/ai/agentic-system.md).
 */
@Component
public class JwtTokenValidator {
 private static final Logger log = LoggerFactory.getLogger(JwtTokenValidator.class);

 public record AuthenticatedUser(String userId, String tier, List<String> roles) {}

 private final WebClient webClient;
 private final String jwksUrl;
 private final AtomicReference<JWKSet> cachedJwks = new AtomicReference<>();
 private volatile long lastFetchTime = 0;

 public JwtTokenValidator(WebClient.Builder webClientBuilder,
   @Value("${auth.jwks-url:${services.identity-url:http://localhost:8081}/api/v1/auth/.well-known/jwks.json}") String jwksUrl) {
  this.webClient = webClientBuilder.build();
  this.jwksUrl = jwksUrl;
 }

 public void setCachedJwks(JWKSet jwkSet) {
  this.cachedJwks.set(jwkSet);
  this.lastFetchTime = System.currentTimeMillis();
 }

 public Mono<AuthenticatedUser> validate(String token) {
  return Mono.defer(() -> {
   try {
    SignedJWT signedJwt = SignedJWT.parse(token);
    if (!JWSAlgorithm.RS256.equals(signedJwt.getHeader().getAlgorithm())) {
     return Mono.error(new IllegalArgumentException("Unsupported JWT algorithm: " + signedJwt.getHeader().getAlgorithm()));
    }
    String kid = signedJwt.getHeader().getKeyID();
    return getJwkSet(kid).flatMap(jwks -> {
     try {
      JWK key = jwks.getKeyByKeyId(kid);
      if (key == null && !jwks.getKeys().isEmpty()) {
       key = jwks.getKeys().get(0);
      }
      if (key == null || !(key instanceof RSAKey rsaKey)) {
       return Mono.error(new IllegalArgumentException("Public key not found for keyId: " + kid));
      }
      if (!signedJwt.verify(new RSASSAVerifier(rsaKey.toRSAPublicKey()))) {
       return Mono.error(new IllegalArgumentException("Invalid JWT signature"));
      }

      JWTClaimsSet claims = signedJwt.getJWTClaimsSet();
      Date exp = claims.getExpirationTime();
      if (exp != null && exp.before(Date.from(Instant.now()))) {
       return Mono.error(new IllegalArgumentException("JWT token has expired"));
      }

      String sub = claims.getSubject();
      if (sub == null || sub.isBlank()) {
       return Mono.error(new IllegalArgumentException("JWT subject claim is missing"));
      }

      String tier = claims.getStringClaim("tier");
      if (tier == null || tier.isBlank()) tier = "STANDARD";

      List<String> roles = claims.getStringListClaim("roles");
      if (roles == null) roles = List.of("ROLE_USER");

      return Mono.just(new AuthenticatedUser(sub, tier, roles));
     } catch (Exception e) {
      return Mono.error(e);
     }
    });
   } catch (Exception e) {
    return Mono.error(new IllegalArgumentException("Malformed JWT token: " + e.getMessage(), e));
   }
  });
 }

 private Mono<JWKSet> getJwkSet(String requiredKid) {
  JWKSet existing = cachedJwks.get();
  long now = System.currentTimeMillis();
  if (existing != null && (requiredKid == null || existing.getKeyByKeyId(requiredKid) != null) && (now - lastFetchTime < 300_000)) {
   return Mono.just(existing);
  }
  return fetchJwks();
 }

 private Mono<JWKSet> fetchJwks() {
  return webClient.get().uri(jwksUrl).retrieve()
   .bodyToMono(String.class)
   .map(json -> {
    try {
     JWKSet parsed = JWKSet.parse(json);
     cachedJwks.set(parsed);
     lastFetchTime = System.currentTimeMillis();
     log.info("Refreshed JWKS keys from {}: {} keys", jwksUrl, parsed.getKeys().size());
     return parsed;
    } catch (Exception e) {
     throw new IllegalStateException("Failed to parse JWKS: " + e.getMessage(), e);
    }
   })
   .onErrorResume(err -> {
    JWKSet existing = cachedJwks.get();
    if (existing != null) {
     log.warn("Failed to fetch fresh JWKS, falling back to cached keys: {}", err.getMessage());
     return Mono.just(existing);
    }
    return Mono.error(new IllegalStateException("Unable to fetch JWKS from " + jwksUrl + ": " + err.getMessage(), err));
   });
 }
}

