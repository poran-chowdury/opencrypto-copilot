package dev.opencrypto.aicopilotservice.engine;

import java.time.Duration;
import java.util.Optional;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.ollama.management.PullModelStrategy;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The model clients the platform gives engines (docs/ai/agentic-system.md): {@link #reasoning()} is
 * Claude for complex reasoning, {@link #local()} an Ollama model for steps that see personal data.
 * Each is empty until configured, so the service starts without keys and the deterministic engine
 * keeps working.
 *
 * Built here instead of by Spring AI auto-configuration, which expects one chat model per
 * application and fails at start-up without an API key.
 */
@Component
public class Models {
 public record Info(String provider, String model, boolean configured, String baseUrl) {}

 private static final Logger log = LoggerFactory.getLogger(Models.class);
 /** One quick retry on transient errors (429/5xx): a chat request is interactive, it can't wait minutes. */
 private static final RetryTemplate RETRY = RetryTemplate.builder().maxAttempts(2).fixedBackoff(1000).retryOn(TransientAiException.class).build();

 private final ObservationRegistry observations;
 private final ToolCallingManager toolCalling;
 private final ChatModel reasoning, local;
 private final Info reasoningInfo, localInfo;

 public Models(LlmSettings llm, LocalModelSettings localModel, RestClient.Builder internalHttp, ObjectProvider<ObservationRegistry> observations) {
  this.observations = observations.getIfAvailable(() -> ObservationRegistry.NOOP);
  this.toolCalling = DefaultToolCallingManager.builder().observationRegistry(this.observations).build();

  boolean claude = llm.configured() && "anthropic".equalsIgnoreCase(llm.provider());
  if (llm.configured() && !claude) log.warn("LLM_PROVIDER={} is not supported; only 'anthropic' is. No reasoning model configured.", llm.provider());
  this.reasoning = claude ? claude(llm) : null;
  this.reasoningInfo = new Info("anthropic", llm.modelOrDefault(), claude, blankToNull(llm.baseUrl()));

  this.local = localModel.configured() ? ollama(localModel, internalHttp) : null;
  this.localInfo = new Info("ollama", Optional.ofNullable(localModel.model()).orElse(""), localModel.configured(), blankToNull(localModel.baseUrl()));
  log.info("Model clients: reasoning={}, local={}", reasoningInfo, localInfo);
 }

 /** Claude, for complex reasoning. Only ever send it redacted text (step 8). */
 public Optional<ChatModel> reasoning() { return Optional.ofNullable(reasoning); }

 /** The local Ollama model, for steps that see personal data. */
 public Optional<ChatModel> local() { return Optional.ofNullable(local); }

 /** A fresh {@link ChatClient} builder over the reasoning model, with Spring AI observations (traces) on. */
 public Optional<ChatClient.Builder> reasoningClient() { return reasoning().map(this::client); }

 public Optional<ChatClient.Builder> localClient() { return local().map(this::client); }

 /** For a hand-written tool loop: executes the tool calls a model response asks for. */
 public ToolCallingManager toolCallingManager() { return toolCalling; }

 public Info reasoningInfo() { return reasoningInfo; }
 public Info localInfo() { return localInfo; }

 private ChatClient.Builder client(ChatModel model) { return ChatClient.builder(model, observations, null, null); }

 private ChatModel claude(LlmSettings s) {
  // A plain RestClient, not Spring's shared builder: internal headers (correlation id, trace
  // context) have no reason to leave for a third-party API.
  AnthropicApi.Builder api = AnthropicApi.builder().apiKey(s.apiKey())
   .restClientBuilder(RestClient.builder().requestFactory(timeouts(s.timeoutSecondsOrDefault())));
  if (s.baseUrl() != null && !s.baseUrl().isBlank()) api.baseUrl(s.baseUrl());
  return AnthropicChatModel.builder()
   .anthropicApi(api.build())
   .defaultOptions(AnthropicChatOptions.builder().model(s.modelOrDefault()).maxTokens(s.maxTokensOrDefault()).build())
   .toolCallingManager(toolCalling)
   .retryTemplate(RETRY)
   .observationRegistry(observations)
   .build();
 }

 private ChatModel ollama(LocalModelSettings s, RestClient.Builder internalHttp) {
  OllamaApi api = OllamaApi.builder().baseUrl(s.baseUrl())
   .restClientBuilder(internalHttp.clone().requestFactory(timeouts(s.timeoutSecondsOrDefault()))).build();
  return OllamaChatModel.builder()
   .ollamaApi(api)
   .defaultOptions(OllamaChatOptions.builder()
    .model(s.model())
    .temperature(0.1)
    .numCtx(2048)
    .numPredict(250)
    .build())
   // Never download a multi-GB model as a side effect of starting the service.
   .modelManagementOptions(ModelManagementOptions.builder().pullModelStrategy(PullModelStrategy.NEVER).build())
   .toolCallingManager(toolCalling)
   .retryTemplate(RETRY)
   .observationRegistry(observations)
   .build();
 }

 private static SimpleClientHttpRequestFactory timeouts(int readSeconds) {
  SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
  factory.setConnectTimeout(Duration.ofSeconds(5));
  factory.setReadTimeout(Duration.ofSeconds(readSeconds));
  return factory;
 }

 private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
