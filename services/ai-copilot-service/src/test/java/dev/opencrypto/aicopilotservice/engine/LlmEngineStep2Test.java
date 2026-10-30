package dev.opencrypto.aicopilotservice.engine;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import dev.opencrypto.aicopilotservice.engine.CopilotEngine.ChatRequest;
import dev.opencrypto.aicopilotservice.engine.CopilotEngine.ChatResponse;
import dev.opencrypto.aicopilotservice.tools.Caller;
import dev.opencrypto.aicopilotservice.tools.PlatformClient;

/**
 * Step 2 of docs/ai/agentic-system.md: verifies that the LLM copilot engine can inspect tools,
 * execute tool callbacks, receive tool observations, and ground its answer.
 */
@SpringBootTest(properties = {
 "copilot.knowledge.path=../../knowledge",
 "copilot.llm.provider=",
 "copilot.llm.api-key=",
 "copilot.local-llm.model=qwen2.5:3b"
})
class LlmEngineStep2Test {
 static final List<JsonNode> requests = new CopyOnWriteArrayList<>();
 static final HttpServer ollama = fakeOllama();

 @DynamicPropertySource static void ollamaUrl(DynamicPropertyRegistry r) {
  r.add("copilot.local-llm.base-url", () -> "http://localhost:" + ollama.getAddress().getPort());
 }

 @AfterAll static void stop() { ollama.stop(0); }

 @Autowired List<CopilotEngine> engines;
 @MockitoBean PlatformClient platform;

 @BeforeEach void reset() { requests.clear(); }

 @Test void engineNamedLlmIsRegisteredAndConnected() {
  CopilotEngine engine = engines.stream().filter(e -> e.name().equals("llm")).findFirst()
   .orElseThrow(() -> new AssertionError("No engine named 'llm' registered"));
  assertThat(engine.llmConnected()).isTrue();
 }

 @Test void toolCallingLoopExecutesToolAndGroundsResponse() {
  when(platform.portfolio("step2-user")).thenReturn(Map.of("totalValueUsd", 10000.0, "balances", List.of(Map.of("symbol", "BTC", "amount", 0.5))));

  CopilotEngine engine = engines.stream().filter(e -> e.name().equals("llm")).findFirst().orElseThrow();
  ChatResponse r = engine.reply(new Caller("step2-user", "user"), new ChatRequest("What's my portfolio worth?", List.of()));

  assertThat(r.answer()).isEqualTo("Your portfolio holds 0.5 BTC and $10,000 USD.");
  assertThat(r.engine()).isEqualTo("llm");
  assertThat(r.mode()).isEqualTo("LLM");
  assertThat(r.userId()).isEqualTo("step2-user");

  // Verify tool trace is captured
  assertThat(r.toolsExecuted()).hasSize(1);
  var call = r.toolsExecuted().getFirst();
  assertThat(call.name()).isEqualTo("get_portfolio");
  assertThat(call.ok()).isTrue();
  assertThat(call.durationMs()).isGreaterThanOrEqualTo(0);

  // Verify requests sent to Ollama
  assertThat(requests).hasSize(2);
  // Round 1: offered tools
  JsonNode round1 = requests.get(0);
  assertThat(round1.path("tools")).isNotEmpty();
  // Round 2: returned tool execution output
  JsonNode round2 = requests.get(1);
  assertThat(round2.path("messages")).isNotEmpty();
 }

 @Test void swapQuoteToolEmitsProposedActionForUserConfirmation() {
  when(platform.quote(eq("step2-user"), eq("USD"), eq("BTC"), any()))
   .thenReturn(Map.of("quoteId", "q-123", "fromSymbol", "USD", "toSymbol", "BTC", "inputAmount", 100, "estimatedOutput", 0.0011, "fee", 0.1, "rate", 88000, "expiresAt", "2026-10-30T00:00:30Z"));

  CopilotEngine engine = engines.stream().filter(e -> e.name().equals("llm")).findFirst().orElseThrow();
  ChatResponse r = engine.reply(new Caller("step2-user", "user"), new ChatRequest("Swap 100 USD to BTC", List.of()));

  assertThat(r.proposedAction()).isNotNull();
  assertThat(r.proposedAction().type()).isEqualTo("EXECUTE_SWAP_QUOTE");
  assertThat(r.proposedAction().requiresUserConfirmation()).isTrue();
  assertThat(r.proposedAction().payload()).containsEntry("quoteId", "q-123").containsEntry("fromSymbol", "USD");
  assertThat(r.caveats()).anyMatch(c -> c.contains("The copilot cannot execute trades"));
 }

 private static HttpServer fakeOllama() {
  try {
   HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
   ObjectMapper json = new ObjectMapper();
   server.createContext("/api/chat", exchange -> {
    JsonNode req = json.readTree(exchange.getRequestBody());
    requests.add(req);

    ObjectNode resp = json.createObjectNode()
     .put("model", "qwen2.5:3b")
     .put("created_at", "2026-10-30T00:00:00Z")
     .put("done", true)
     .put("done_reason", "stop")
     .put("prompt_eval_count", 50)
     .put("eval_count", 20);

    JsonNode messages = req.path("messages");
    boolean hasToolResult = false;
    if (messages.isArray()) {
     for (JsonNode m : messages) {
      if ("tool".equals(m.path("role").asText())) {
       hasToolResult = true;
       break;
      }
     }
    }

    if (!hasToolResult) {
     // Check if user is asking for a swap or portfolio
     boolean isSwap = false;
     if (messages.isArray()) {
      for (JsonNode m : messages) {
       if ("user".equals(m.path("role").asText()) && m.path("content").asText().toLowerCase().contains("swap")) {
        isSwap = true;
       }
      }
     }

     ObjectNode msg = json.createObjectNode().put("role", "assistant").put("content", "");
     ArrayNode toolCalls = msg.putArray("tool_calls");
     ObjectNode call = toolCalls.addObject();
     if (isSwap) {
      call.putObject("function").put("name", "get_swap_quote")
       .putObject("arguments").put("fromSymbol", "USD").put("toSymbol", "BTC").put("amount", 100);
     } else {
      call.putObject("function").put("name", "get_portfolio").putObject("arguments");
     }
     resp.set("message", msg);
    } else {
     // Round 2: final answer
     String content = "Here is your requested information.";
     if (messages.isArray()) {
      for (JsonNode m : messages) {
       if (m.path("content").asText().toLowerCase().contains("portfolio")) {
        content = "Your portfolio holds 0.5 BTC and $10,000 USD.";
        break;
       }
      }
     }
     resp.set("message", json.createObjectNode().put("role", "assistant").put("content", content));
    }

    byte[] body = json.writeValueAsBytes(resp);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
   });
   server.start();
   return server;
  } catch (IOException e) {
   throw new IllegalStateException(e);
  }
 }
}
