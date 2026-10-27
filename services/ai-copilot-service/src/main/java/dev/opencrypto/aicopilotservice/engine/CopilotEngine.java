package dev.opencrypto.aicopilotservice.engine;

import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import dev.opencrypto.aicopilotservice.tools.Caller;
import dev.opencrypto.aicopilotservice.tools.ToolCall;

/**
 * The seam where an AI implementation plugs in.
 *
 * Every engine is a Spring bean with a unique {@link #name()}. The default comes from
 * {@code copilot.engine}; a request can pick another with {@code "engine": "<name>"}, so you can
 * compare engines side by side (UI dropdown, evaluation/run_eval.py --engine). The built-in
 * {@link DeterministicCopilotEngine} answers with keyword rules and is the baseline to beat.
 *
 * To experiment with an LLM, add e.g. an {@code LlmCopilotEngine} that sends
 * {@code CopilotTools.definitions()} to a model, runs requested tools via
 * {@code CopilotTools.call(caller, ...)} and loops until the model answers.
 * See docs/ai/build-your-copilot.md.
 */
public interface CopilotEngine {
 /** Unique engine name used in requests and {@code copilot.engine}. */
 String name();

 /** True when answers come from a language model rather than fixed rules. */
 boolean llmConnected();

 ChatResponse reply(Caller caller, ChatRequest request);

 record ChatMessage(@NotBlank @Pattern(regexp = "user|assistant") String role, @NotNull @Size(max = 8000) String content) {}

 /** {@code engine} is optional; when null the configured default engine answers. */
 record ChatRequest(@NotBlank @Size(max = 2000) String message, @Size(max = 50) List<@Valid ChatMessage> history, String engine) {
  public ChatRequest(String message, List<ChatMessage> history) { this(message, history, null); }
 }

 /** A state-changing step the copilot suggests but must never perform itself. */
 record ProposedAction(String type, String description, Map<String,Object> payload, boolean requiresUserConfirmation) {}

 /** {@code usage} is free-form (e.g. inputTokens, outputTokens, estimatedCostUsd) for evaluation runs. */
 record ChatResponse(String userId, String answer, String mode, String engine, boolean llmConnected,
   List<ToolCall> toolsExecuted, ProposedAction proposedAction, List<String> caveats, Map<String,Object> usage) {}
}
