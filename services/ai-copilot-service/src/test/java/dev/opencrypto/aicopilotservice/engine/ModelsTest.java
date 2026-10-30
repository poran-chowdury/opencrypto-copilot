package dev.opencrypto.aicopilotservice.engine;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.Map;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.RestClient;

class ModelsTest {
 private static final LocalModelSettings NO_LOCAL = new LocalModelSettings("http://localhost:11435", "", null);

 @Test void withoutKeysThereAreNoClientsAndTheServiceStillStarts() {
  Models models = models(new LlmSettings(null, null, null, null, null, null, null), NO_LOCAL);
  assertThat(models.reasoning()).isEmpty();
  assertThat(models.local()).isEmpty();
  assertThat(models.reasoningInfo()).isEqualTo(new Models.Info("anthropic", "claude-opus-5-5", false, null));
  assertThat(models.localInfo().configured()).isFalse();
 }

 @Test void anthropicWithAKeyBuildsAClaudeClient() {
  Models models = models(new LlmSettings("anthropic", "", "test-key", null, null, null, null), NO_LOCAL);
  assertThat(models.reasoning()).get().isInstanceOf(AnthropicChatModel.class);
  assertThat(models.reasoningInfo().model()).isEqualTo("claude-opus-5-5");   // blank model falls back to the default
  assertThat(models.reasoningClient()).isPresent();
 }

 @Test void anUnsupportedProviderIsReportedNotGuessed() {
  Models models = models(new LlmSettings("someone-else", "x", "key", null, null, null, null), NO_LOCAL);
  assertThat(models.reasoning()).isEmpty();
  assertThat(models.reasoningInfo().configured()).isFalse();
 }

 @Test void aLocalModelNeedsANameAndIsNeverPulledAtStartUp() {
  // Building it makes no network call (pull strategy NEVER), so this works without Ollama running.
  Models models = models(new LlmSettings(null, null, null, null, null, null, null), new LocalModelSettings("http://localhost:1", "qwen3:4b", 5));
  assertThat(models.local()).get().isInstanceOf(OllamaChatModel.class);
  assertThat(models.localInfo()).isEqualTo(new Models.Info("ollama", "qwen3:4b", true, "http://localhost:1"));
 }

 @Test void pricingEstimatesCostPerMillionTokens() {
  ModelPricing pricing = new ModelPricing(Map.of("claude-opus-5-5", new ModelPricing.Price(new BigDecimal("4.00"), new BigDecimal("20.00"))));
  // 2,000 in × $4/M + 500 out × $20/M = 0.008 + 0.010
  assertThat(pricing.estimateUsd("claude-opus-5-5", 2_000, 500)).get().isEqualTo(new BigDecimal("0.018"));
  assertThat(pricing.estimateUsd("qwen3:4b", 2_000, 500)).isEmpty();
 }

 private static Models models(LlmSettings llm, LocalModelSettings local) {
  var observations = new StaticListableBeanFactory(Map.of("observationRegistry", ObservationRegistry.NOOP)).getBeanProvider(ObservationRegistry.class);
  return new Models(llm, local, RestClient.builder(), observations);
 }
}
