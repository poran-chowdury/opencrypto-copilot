package dev.opencrypto.identityservice;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;

class AuthServiceTest {
 private RsaKeyProvider keyProvider;
 private UserRepository userRepository;
 private TokenService tokenService;
 private AuthController authController;

 @BeforeEach
 void setUp() {
  keyProvider = new RsaKeyProvider();
  userRepository = new UserRepository();
  tokenService = new TokenService(keyProvider);
  authController = new AuthController(keyProvider, userRepository, tokenService);
 }

 @Test
 void jwksEndpointReturnsPublicRsaKey() {
  Map<String, Object> jwks = authController.jwks();
  assertThat(jwks).containsKey("keys");
  assertThat(authController.status().get("status")).isEqualTo("UP");
 }

 @Test
 void loginIssuesSignedJwtWithTierClaims() throws Exception {
  ResponseEntity<AuthController.AuthResponse> response = authController.login(
   new AuthController.LoginRequest("alice", "password")
  );
  assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  AuthController.AuthResponse auth = response.getBody();
  assertThat(auth).isNotNull();
  assertThat(auth.tokenType()).isEqualTo("Bearer");
  assertThat(auth.user().userId()).isEqualTo("alice");
  assertThat(auth.user().tier()).isEqualTo("PREMIUM");

  // Verify cryptographic signature
  SignedJWT signedJwt = SignedJWT.parse(auth.token());
  boolean verified = signedJwt.verify(new RSASSAVerifier(keyProvider.toPublicKey()));
  assertThat(verified).isTrue();
  assertThat(signedJwt.getJWTClaimsSet().getSubject()).isEqualTo("alice");
  assertThat(signedJwt.getJWTClaimsSet().getStringClaim("tier")).isEqualTo("PREMIUM");
 }

 @Test
 void meEndpointValidatesBearerToken() {
  var login = authController.login(new AuthController.LoginRequest("bob", null)).getBody();
  assertThat(login).isNotNull();

  ResponseEntity<AuthController.UserDto> me = authController.me("Bearer " + login.token());
  assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
  assertThat(me.getBody().userId()).isEqualTo("bob");
  assertThat(me.getBody().tier()).isEqualTo("STANDARD");
 }

 @Test
 void invalidCredentialsAndTamperedTokenFail() {
  assertThatThrownBy(() -> authController.login(new AuthController.LoginRequest("alice", "wrong-password")))
   .isInstanceOf(ResponseStatusException.class);

  assertThatThrownBy(() -> authController.me("Bearer invalid.token.payload"))
   .isInstanceOf(ResponseStatusException.class);
 }
}

