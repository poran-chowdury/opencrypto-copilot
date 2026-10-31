package dev.opencrypto.identityservice;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

/**
 * Manages the RSA keypair used to sign and publish OIDC JSON Web Keys (JWKS).
 * Part of Step 5 (docs/ai/agentic-system.md).
 */
@Component
public class RsaKeyProvider {
 private final RSAKey rsaJwk;

 public RsaKeyProvider() {
  try {
   KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
   generator.initialize(2048);
   KeyPair keyPair = generator.generateKeyPair();
   this.rsaJwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
    .privateKey((RSAPrivateKey) keyPair.getPrivate())
    .keyUse(KeyUse.SIGNATURE)
    .algorithm(JWSAlgorithm.RS256)
    .keyID("opencrypto-key-" + UUID.randomUUID().toString().substring(0, 8))
    .build();
  } catch (Exception e) {
   throw new IllegalStateException("Failed to initialize RSA keypair for JWT signing", e);
  }
 }

 public RSAKey rsaJwk() { return rsaJwk; }
 public RSAPublicKey toPublicKey() throws Exception { return rsaJwk.toRSAPublicKey(); }
 public RSAPrivateKey toPrivateKey() throws Exception { return rsaJwk.toRSAPrivateKey(); }
 public String keyId() { return rsaJwk.getKeyID(); }
 public JWKSet toPublicJwkSet() { return new JWKSet(rsaJwk.toPublicJWK()); }
}

