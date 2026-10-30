package dev.opencrypto.aicopilotservice.engine;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import dev.opencrypto.aicopilotservice.engine.CopilotEngine.ChatRequest;
import dev.opencrypto.aicopilotservice.tools.Caller;
import dev.opencrypto.platform.web.ApiException;

class LocalCopilotEngineTest {
 private static final Caller CALLER = new Caller("u1", "user");

 @Test void withoutAModelItSaysSoInsteadOfAnswering() {
  LocalCopilotEngine engine = new LocalCopilotEngine(models(new LocalModelSettings("http://localhost:11435", "", null)));
  assertThat(engine.llmConnected()).isFalse();
  assertThatThrownBy(() -> engine.reply(CALLER, new ChatRequest("hi", List.of())))
   .isInstanceOfSatisfying(ApiException.class, e -> {
    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(e.code()).isEqualTo("LOCAL_MODEL_NOT_CONFIGURED");
   });
 }

 @Test void anUnreachableModelIsAnOutageNotAnAnswer() {
  // Port 1: nothing listens, the connection is refused at once.
  LocalCopilotEngine engine = new LocalCopilotEngine(models(new LocalModelSettings("http://localhost:1", "qwen2.5:3b", 2)));
  assertThat(engine.llmConnected()).isTrue();
  assertThatThrownBy(() -> engine.reply(CALLER, new ChatRequest("hi", List.of())))
   .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("LOCAL_MODEL_UNAVAILABLE"));
 }

 private static Models models(LocalModelSettings local) {
  var observations = new StaticListableBeanFactory(Map.of("observationRegistry", ObservationRegistry.NOOP)).getBeanProvider(ObservationRegistry.class);
  return new Models(new LlmSettings(null, null, null, null, null, null, null), local, RestClient.builder(), observations);
 }
}
