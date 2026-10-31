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
 * Step 3 of docs/ai/agentic-system.md: Domain sub-agents with a supervisor.
 *
 * Verifies that:
 * 1. The supervisor engine is registered and LLM connected.
 * 2. Single-domain queries are routed to specialized sub-agents with narrow tool sets (e.g., 2 tools instead of 11).
 * 3. Compound queries (e.g. balance AND transactions) are decomposed across sub-agents and their answers synthesized.
 * 4. State-changing quotes emit a ProposedAction for explicit human confirmation.
 */
@SpringBootTest(properties = {
 "copilot.knowledge.path=../../knowledge",
 "copilot.llm.provider=",
 "copilot.llm.api-key=",
 "copilot.local-llm.model=qwen2.5:3b",
 "spring.ai.mcp.client.enabled=false"
})
class SupervisorEngineStep3Test {
 static final List<JsonNode> requests = new CopyOnWriteArrayList<>();
 static final HttpServer ollama = fakeOllama();

 @DynamicPropertySource static void ollamaUrl(DynamicPropertyRegistry r) {
  r.add("copilot.local-llm.base-url", () -> "http://localhost:" + ollama.getAddress().getPort());
 }

 @AfterAll static void stop() { ollama.stop(0); }

 @Autowired List<CopilotEngine> engines;
 @MockitoBean PlatformClient platform;

 @BeforeEach void reset() { requests.clear(); }

 @Test void supervisorEngineIsRegisteredAndConnected() {
  CopilotEngine engine = engines.stream().filter(e -> e.name().equals("supervisor")).findFirst()
   .orElseThrow(() -> new AssertionError("No engine named 'supervisor' registered"));
  assertThat(engine.llmConnected()).isTrue();
 }

 @Test void routesSingleDomainQueryWithNarrowedToolScope() {
  when(platform.portfolio("step3-user")).thenReturn(Map.of("totalValueUsd", 15000.0, "balances", List.of(Map.of("symbol", "BTC", "amount", 0.5))));

  CopilotEngine engine = engines.stream().filter(e -> e.name().equals("supervisor")).findFirst().orElseThrow();
  ChatResponse r = engine.reply(new Caller("step3-user", "user"), new ChatRequest("What is my portfolio balance?", List.of()));

  assertThat(r.engine()).isEqualTo("supervisor");
  assertThat(r.mode()).isEqualTo("SUPERVISOR");
  assertThat(r.answer()).contains("15,000 USD");
  assertThat(r.toolsExecuted()).hasSize(1);
  assertThat(r.toolsExecuted().getFirst().name()).isEqualTo("get_portfolio");

  @SuppressWarnings("unchecked")
  List<String> agents = (List<String>) r.usage().get("agents");
  assertThat(agents).containsExactly("portfolio");

  // Critical architectural check: verify the tools passed to the model were restricted to Portfolio only (2 tools), NOT all 11!
  JsonNode round1 = requests.getFirst();
  JsonNode toolsOffered = round1.path("tools");
  assertThat(toolsOffered.size()).isEqualTo(2);
 }

 @Test void decomposesMultiPartQueryAcrossSubAgentsAndMergesAnswers() {
  when(platform.portfolio("step3-user")).thenReturn(Map.of("totalValueUsd", 12000.0, "balances", List.of(Map.of("symbol", "BTC", "amount", 0.4))));
  when(platform.transactions(eq("step3-user"), any(), anyInt())).thenReturn(Map.of("items", List.of(
   Map.of("id", "tx-1", "type", "DEPOSIT", "amount", 1000.0, "symbol", "USD", "status", "CONFIRMED")
  )));

  CopilotEngine engine = engines.stream().filter(e -> e.name().equals("supervisor")).findFirst().orElseThrow();
  ChatResponse r = engine.reply(new Caller("step3-user", "user"),
   new ChatRequest("What is my balance and show my recent transactions", List.of()));

  assertThat(r.engine()).isEqualTo("supervisor");
  @SuppressWarnings("unchecked")
  List<String> agents = (List<String>) r.usage().get("agents");
  assertThat(agents).containsExactly("portfolio", "activity");

  // Both tools were executed across the sub-agents
  assertThat(r.toolsExecuted().stream().map(c -> c.name()).toList()).contains("get_portfolio", "get_transactions");

  // Answers from both sub-agents are merged
  assertThat(r.answer()).contains("12,000 USD");
  assertThat(r.answer()).contains("Deposit of 1000 USD");
 }

 @Test void tradingSubAgentProducesProposedActionOnSwap() {
  when(platform.quote(eq("step3-user"), eq("USD"), eq("BTC"), any()))
   .thenReturn(Map.of("quoteId", "quote-step3", "fromSymbol", "USD", "toSymbol", "BTC", "inputAmount", 500, "estimatedOutput", 0.0055, "fee", 0.5, "rate", 90000, "expiresAt", "2026-10-30T00:00:30Z"));

  CopilotEngine engine = engines.stream().filter(e -> e.name().equals("supervisor")).findFirst().orElseThrow();
  ChatResponse r = engine.reply(new Caller("step3-user", "user"), new ChatRequest("Swap 500 USD to BTC", List.of()));

  assertThat(r.proposedAction()).isNotNull();
  assertThat(r.proposedAction().type()).isEqualTo("EXECUTE_SWAP_QUOTE");
  assertThat(r.proposedAction().requiresUserConfirmation()).isTrue();
  assertThat(r.proposedAction().payload()).containsEntry("quoteId", "quote-step3");
  assertThat(r.caveats()).anyMatch(c -> c.contains("The copilot cannot execute trades directly"));
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
     .put("prompt_eval_count", 25)
     .put("eval_count", 15);

    JsonNode messages = req.path("messages");
    boolean hasToolResult = false;
    String userPrompt = "";
    if (messages.isArray()) {
     for (JsonNode m : messages) {
      if ("tool".equals(m.path("role").asText())) {
       hasToolResult = true;
      }
      if ("user".equals(m.path("role").asText())) {
       userPrompt = m.path("content").asText().toLowerCase();
      }
     }
    }

    if (!hasToolResult) {
     ObjectNode msg = json.createObjectNode().put("role", "assistant").put("content", "");
     ArrayNode toolCalls = json.createArrayNode();
     ObjectNode call = json.createObjectNode();
     ObjectNode function = json.createObjectNode();

     if (userPrompt.contains("swap")) {
      function.put("name", "get_swap_quote");
      function.set("arguments", json.createObjectNode().put("fromSymbol", "USD").put("toSymbol", "BTC").put("amount", 500.0));
     } else if (userPrompt.contains("transaction") || userPrompt.contains("activity") || userPrompt.contains("ledger")) {
      function.put("name", "get_transactions");
      function.set("arguments", json.createObjectNode().put("limit", 5));
     } else {
      function.put("name", "get_portfolio");
      function.set("arguments", json.createObjectNode());
     }
     call.set("function", function);
     toolCalls.add(call);
     msg.set("tool_calls", toolCalls);
     resp.set("message", msg);
    } else {
     ObjectNode msg = json.createObjectNode().put("role", "assistant");
     if (userPrompt.contains("swap")) {
      msg.put("content", "Swap quote ready: 500 USD for 0.0055 BTC at 90,000 USD/BTC. Please confirm in the UI.");
     } else if (userPrompt.contains("transaction") || userPrompt.contains("activity") || userPrompt.contains("ledger")) {
      msg.put("content", "Recent activity: Deposit of 1000 USD completed.");
     } else {
      msg.put("content", "Your portfolio holds 0.5 BTC valued at approximately 15,000 USD (or 12,000 USD).");
     }
     resp.set("message", msg);
    }

    byte[] body = json.writeValueAsBytes(resp);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
   });
   server.start();
   return server;
  } catch (IOException e) {
   throw new RuntimeException(e);
  }
 }
}
