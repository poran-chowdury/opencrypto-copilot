package dev.opencrypto.aicopilotservice.engine;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import dev.opencrypto.aicopilotservice.engine.CopilotEngine.ChatRequest;
import dev.opencrypto.aicopilotservice.engine.CopilotEngine.ChatResponse;
import dev.opencrypto.aicopilotservice.tools.Caller;

/**
 * "Done" for step 1 of docs/ai/agentic-system.md: an engine named {@code local} that sends the question
 * to the local model with no tools. Fails until you write it; a fake Ollama stands in for the real one.
 */
@SpringBootTest(properties = {"copilot.knowledge.path=../../knowledge", "copilot.llm.provider=", "copilot.llm.api-key=", "copilot.local-llm.model=qwen2.5:3b"})
class LocalEngineStep1Test {
 static final String ANSWER = "I don't have access to your portfolio, so I can't tell you what it is worth.";
 static final List<JsonNode> requests = new CopyOnWriteArrayList<>();
 static final HttpServer ollama = fakeOllama();

 @DynamicPropertySource static void ollamaUrl(DynamicPropertyRegistry r) {
  r.add("copilot.local-llm.base-url", () -> "http://localhost:" + ollama.getAddress().getPort());
 }

 @AfterAll static void stop() { ollama.stop(0); }

 @Autowired List<CopilotEngine> engines;
 @Autowired Models models;

 @BeforeEach void reset() { requests.clear(); }

 /** Platform check: the fake speaks Ollama's protocol, so a failure below is in the engine, not the test. */
 @Test void thePlatformLocalClientTalksToOllama() {
  var response = models.localClient().orElseThrow().build().prompt("hi").call().chatResponse();
  assertThat(response.getResult().getOutput().getText()).isEqualTo(ANSWER);
  assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(43);
  assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(21);
 }

 @Test void aLocalEngineAnswersFromTheModelWithNoTools() {
  CopilotEngine local = engines.stream().filter(e -> e.name().equals("local")).findFirst()
   .orElseThrow(() -> new AssertionError("No engine named 'local' yet. Write LocalCopilotEngine in engine/ (step 1)."));
  assertThat(local.llmConnected()).as("llmConnected() when LOCAL_LLM_MODEL is set").isTrue();

  ChatResponse r = local.reply(new Caller("step1-user", "user"), new ChatRequest("What's my portfolio worth?", List.of()));

  assertThat(r.answer()).as("the model's text, not a template").isEqualTo(ANSWER);
  assertThat(r.engine()).isEqualTo("local");
  assertThat(r.mode()).isEqualTo("LLM");
  assertThat(r.userId()).isEqualTo("step1-user");
  assertThat(r.toolsExecuted()).as("step 1 has no tools").isEmpty();
  assertThat(r.usage()).as("what the Assistant inspector shows")
   .containsEntry("model", "qwen2.5:3b").containsEntry("inputTokens", 43).containsEntry("outputTokens", 21);

  assertThat(requests).hasSize(1);
  JsonNode sent = requests.getFirst();
  assertThat(sent.path("model").asText()).isEqualTo("qwen2.5:3b");
  assertThat(sent.path("tools").isMissingNode() || sent.path("tools").isEmpty()).as("no tools offered to the model").isTrue();
  JsonNode last = sent.path("messages").get(sent.path("messages").size() - 1);
  assertThat(last.path("role").asText()).isEqualTo("user");
  assertThat(last.path("content").asText()).isEqualTo("What's my portfolio worth?");
 }

 @Test void earlierTurnsAreSentOldestFirstBeforeTheNewQuestion() {
  CopilotEngine local = engines.stream().filter(e -> e.name().equals("local")).findFirst().orElseThrow();
  local.reply(new Caller("step1-user", "user"), new ChatRequest("And in euros?", List.of(
   new CopilotEngine.ChatMessage("user", "What's my portfolio worth?"),
   new CopilotEngine.ChatMessage("assistant", "I can't see your portfolio."))));

  List<String> turns = new java.util.ArrayList<>();
  requests.getFirst().path("messages").forEach(m -> turns.add(m.path("role").asText() + ": " + m.path("content").asText()));
  assertThat(turns.getFirst()).startsWith("system: You are the OpenCrypto copilot");
  assertThat(turns.subList(1, turns.size())).containsExactly(
   "user: What's my portfolio worth?", "assistant: I can't see your portfolio.", "user: And in euros?");
 }

 /** Answers POST /api/chat the way Ollama does (non-streaming), and records each request body. */
 private static HttpServer fakeOllama() {
  try {
   HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
   ObjectMapper json = new ObjectMapper();
   server.createContext("/api/chat", exchange -> {
    requests.add(json.readTree(exchange.getRequestBody()));
    byte[] body = json.writeValueAsBytes(json.createObjectNode()
     .put("model", "qwen2.5:3b").put("created_at", "2026-10-28T00:00:00Z")
     .<com.fasterxml.jackson.databind.node.ObjectNode>set("message", json.createObjectNode().put("role", "assistant").put("content", ANSWER))
     .put("done", true).put("done_reason", "stop")
     .put("prompt_eval_count", 43).put("eval_count", 21)
     .put("total_duration", 1_000_000).put("prompt_eval_duration", 1).put("eval_duration", 1).put("load_duration", 1));
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
   });
   server.start();
   return server;
  } catch (IOException e) { throw new IllegalStateException(e); }
 }
}
