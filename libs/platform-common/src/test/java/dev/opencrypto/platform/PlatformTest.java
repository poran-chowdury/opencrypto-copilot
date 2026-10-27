package dev.opencrypto.platform;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import dev.opencrypto.platform.web.ApiException;
import dev.opencrypto.platform.web.CorrelationId;
import dev.opencrypto.platform.web.DemoUser;

class PlatformTest {
 @Test void wellFormedCorrelationIdIsKept() {
  assertThat(CorrelationId.sanitizeOrCreate("abc-123_X.y")).isEqualTo("abc-123_X.y");
 }

 @Test void injectedCorrelationIdIsReplaced() {
  String id = CorrelationId.sanitizeOrCreate("abc\nFAKE LOG LINE");
  assertThat(id).doesNotContain("\n").hasSize(36);
  assertThat(CorrelationId.sanitizeOrCreate(null)).hasSize(36);
 }

 @Test void demoUserIsValidated() {
  assertThat(DemoUser.resolve(null)).isEqualTo(DemoUser.DEFAULT);
  assertThat(DemoUser.resolve("alice.b-1")).isEqualTo("alice.b-1");
  assertThatThrownBy(() -> DemoUser.resolve("x' OR 1=1 --")).isInstanceOf(ApiException.class);
 }
}
