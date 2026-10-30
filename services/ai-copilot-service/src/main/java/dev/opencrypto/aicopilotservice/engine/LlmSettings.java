package dev.opencrypto.aicopilotservice.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the reasoning model, bound from {@code copilot.llm.*}
 * (env: LLM_PROVIDER, LLM_MODEL, LLM_API_KEY, LLM_BASE_URL). {@link Models} turns them into a
 * Claude client when {@code provider=anthropic} and a key is set; your engine reads the limits.
 * The API key is never returned by any endpoint.
 */
@ConfigurationProperties(prefix = "copilot.llm")
public record LlmSettings(String provider, String model, String apiKey, String baseUrl, Integer maxToolRounds, Integer timeoutSeconds, Integer maxTokens) {
 public static final String DEFAULT_MODEL = "claude-opus-5-5";

 public boolean configured() { return provider != null && !provider.isBlank() && apiKey != null && !apiKey.isBlank(); }
 public String modelOrDefault() { return model == null || model.isBlank() ? DEFAULT_MODEL : model; }
 public int maxToolRoundsOrDefault() { return maxToolRounds == null ? 5 : maxToolRounds; }
 public int timeoutSecondsOrDefault() { return timeoutSeconds == null ? 120 : timeoutSeconds; }
 public int maxTokensOrDefault() { return maxTokens == null ? 16000 : maxTokens; }

 @Override public String toString() {
  return "LlmSettings[provider=%s, model=%s, apiKey=%s, baseUrl=%s]".formatted(provider, model, configured() ? "***" : "<unset>", baseUrl);
 }
}
