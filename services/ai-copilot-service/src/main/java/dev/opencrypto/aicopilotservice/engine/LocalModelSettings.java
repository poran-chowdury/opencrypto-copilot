package dev.opencrypto.aicopilotservice.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The local model (Ollama) for steps that see personal data, bound from {@code copilot.local-llm.*}
 * (env: LOCAL_LLM_BASE_URL, LOCAL_LLM_MODEL). Not configured until a model name is set.
 */
@ConfigurationProperties(prefix = "copilot.local-llm")
public record LocalModelSettings(String baseUrl, String model, Integer timeoutSeconds) {
 public boolean configured() { return baseUrl != null && !baseUrl.isBlank() && model != null && !model.isBlank(); }
 public int timeoutSecondsOrDefault() { return timeoutSeconds == null ? 300 : timeoutSeconds; }
}
