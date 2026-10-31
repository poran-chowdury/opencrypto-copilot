package dev.opencrypto.identityservice;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Issues cryptographic RS256 JWT tokens with tier and identity claims.
 * Part of Step 5 (docs/ai/agentic-system.md).
 */
@Service
public class TokenService {
 private final RsaKeyProvider keyProvider;

 public TokenService(RsaKeyProvider keyProvider) {
  this.keyProvider = keyProvider;
 }

 public String issueToken(UserRepository.UserAccount user, long validitySeconds) {
  try {
   Instant now = Instant.now();
   JWTClaimsSet claims = new JWTClaimsSet.Builder()
    .issuer("http://localhost:8080/api/v1/auth")
    .subject(user.userId())
    .issueTime(Date.from(now))
    .expirationTime(Date.from(now.plusSeconds(validitySeconds)))
    .claim("name", user.name())
    .claim("email", user.email())
    .claim("tier", user.tier())
    .claim("roles", user.roles())
    .build();

   JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
    .keyID(keyProvider.keyId())
    .build();

   SignedJWT signedJwt = new SignedJWT(header, claims);
   signedJwt.sign(new RSASSASigner(keyProvider.toPrivateKey()));
   return signedJwt.serialize();
  } catch (Exception e) {
   throw new IllegalStateException("Failed to sign JWT token", e);
  }
 }
}

