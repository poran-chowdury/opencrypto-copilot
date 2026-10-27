package dev.opencrypto.aicopilotservice.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the LLM engine you build, bound from {@code copilot.llm.*}
 * (env: LLM_PROVIDER, LLM_MODEL, LLM_API_KEY, LLM_BASE_URL). Inject it into your engine.
 * The API key is never returned by any endpoint.
 */
@ConfigurationProperties(prefix = "copilot.llm")
public record LlmSettings(String provider, String model, String apiKey, String baseUrl, Integer maxToolRounds, Integer timeoutSeconds) {
 public boolean configured() { return provider != null && !provider.isBlank() && apiKey != null && !apiKey.isBlank(); }
 public int maxToolRoundsOrDefault() { return maxToolRounds == null ? 5 : maxToolRounds; }
 public int timeoutSecondsOrDefault() { return timeoutSeconds == null ? 30 : timeoutSeconds; }

 @Override public String toString() {
  return "LlmSettings[provider=%s, model=%s, apiKey=%s, baseUrl=%s]".formatted(provider, model, configured() ? "***" : "<unset>", baseUrl);
 }
}
