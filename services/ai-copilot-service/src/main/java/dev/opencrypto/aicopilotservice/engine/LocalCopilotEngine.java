package dev.opencrypto.aicopilotservice.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;
import dev.opencrypto.aicopilotservice.tools.Caller;
import dev.opencrypto.platform.web.ApiException;

/**
 * Step 1 of docs/ai/agentic-system.md: the local model with <b>no tools</b>. It can't see the user's
 * portfolio, so it can only refuse or guess. That is the "demo" baseline the tool loop (step 2) has to
 * beat. Notes: docs/engineering/14-plain-llm-baseline.md.
 */
@Component
public class LocalCopilotEngine implements CopilotEngine {
 static final String SYSTEM_PROMPT = """
  You are the OpenCrypto copilot, an assistant inside a simulated crypto trading app.
  You have no access to the user's portfolio, balances, transactions or live prices.
  If a question needs that data, say you can't see it. Never invent a number.
  Keep answers short. You cannot place trades. Nothing you say is financial advice.
  """;

 private static final Logger log = LoggerFactory.getLogger(LocalCopilotEngine.class);
 private final Models models;

 public LocalCopilotEngine(Models models) { this.models = models; }

 @Override public String name() { return "local"; }

 /** Only true when there is a model behind it: the UI and /status show this per engine. */
 @Override public boolean llmConnected() { return models.localClient().isPresent(); }

 @Override public ChatResponse reply(Caller caller, ChatRequest request) {
  ChatClient client = models.localClient()
   .orElseThrow(() -> ApiException.unavailable("LOCAL_MODEL_NOT_CONFIGURED", "The local engine needs LOCAL_LLM_MODEL (and Ollama running)."))
   .build();

  org.springframework.ai.chat.model.ChatResponse response;
  try {
   response = client.prompt()
    .system(SYSTEM_PROMPT)
    .messages(history(request))      // earlier turns, oldest first: the model has no memory of its own
    .user(request.message())         // the new question goes last
    .call().chatResponse();
  } catch (RuntimeException e) {
   // Ollama down, model not pulled, read timeout... An outage is not an answer: fail loudly with a code.
   log.warn("Local model call failed: {}", e.toString());
   throw ApiException.unavailable("LOCAL_MODEL_UNAVAILABLE", "The local model did not answer: " + e.getMessage());
  }

  var result = response.getResult();
  String finish = String.valueOf(result.getMetadata().getFinishReason()).toLowerCase();
  List<String> caveats = new ArrayList<>(List.of("Plain model, no tools: it cannot see your portfolio or live prices.", DeterministicCopilotEngine.NOT_ADVICE));
  // "length" means the model hit its token limit mid-sentence: the text is a fragment, not an answer.
  if (!finish.equals("stop")) caveats.addFirst("The model stopped early (finish reason: " + finish + "); the answer may be incomplete.");

  return new ChatResponse(caller.userId(), result.getOutput().getText(), "LLM", name(), true,
   List.of(), null, caveats, usage(response.getMetadata().getUsage(), finish));
 }

 private static List<Message> history(ChatRequest request) {
  if (request.history() == null) return List.of();
  return request.history().stream()
   .<Message>map(m -> m.role().equals("assistant") ? new AssistantMessage(m.content()) : new UserMessage(m.content()))
   .toList();
 }

 /** The keys the Assistant inspector and run_eval.py read. A local model costs nothing per token, so no estimatedCostUsd. */
 private Map<String, Object> usage(Usage u, String finish) {
  Map<String, Object> m = new LinkedHashMap<>();   // not Map.of: token counts can be null
  m.put("model", models.localInfo().model());
  m.put("inputTokens", u == null ? null : u.getPromptTokens());
  m.put("outputTokens", u == null ? null : u.getCompletionTokens());
  m.put("finishReason", finish);
  return m;
 }
}
