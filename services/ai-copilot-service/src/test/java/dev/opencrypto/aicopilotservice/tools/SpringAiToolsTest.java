package dev.opencrypto.aicopilotservice.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import dev.opencrypto.aicopilotservice.knowledge.Retrievers;

class SpringAiToolsTest {
 private final ObjectMapper json = new ObjectMapper();
 private final PlatformClient platform = mock(PlatformClient.class);
 private final CopilotTools tools = new CopilotTools(platform, mock(Retrievers.class), json);
 private final SpringAiTools adapter = new SpringAiTools(tools, json);
 private final Caller alice = new Caller("alice", "user");

 @Test void everyToolIsExposedWithItsJsonSchema() throws Exception {
  List<ToolCallback> callbacks = adapter.forCaller(alice, new ArrayList<>());
  assertThat(callbacks).extracting(c -> c.getToolDefinition().name())
   .containsExactlyElementsOf(tools.definitions().stream().map(ToolDefinition::name).toList());
  ToolCallback quote = byName(callbacks, "get_swap_quote");
  Map<?, ?> schema = json.readValue(quote.getToolDefinition().inputSchema(), Map.class);
  assertThat(schema.get("required")).isEqualTo(List.of("fromSymbol", "toSymbol", "amount"));
 }

 @Test void theCallerComesFromTheBindingNotFromModelInput() throws Exception {
  when(platform.portfolio("alice")).thenReturn(Map.of("userId", "alice", "totalValueUsd", 100));
  List<ToolCall> trace = new ArrayList<>();
  // The model tries to read someone else's portfolio: the input is ignored for identity.
  String result = byName(adapter.forCaller(alice, trace), "get_portfolio").call("{\"userId\": \"bob\"}");
  verify(platform).portfolio("alice");
  verify(platform, never()).portfolio("bob");
  assertThat(json.readValue(result, Map.class)).containsEntry("userId", "alice");
  assertThat(trace).singleElement().satisfies(c -> { assertThat(c.name()).isEqualTo("get_portfolio"); assertThat(c.ok()).isTrue(); });
 }

 @Test void failuresGoBackToTheModelAsDataAndAreTraced() throws Exception {
  when(platform.asset("NOPE")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown asset NOPE"));
  List<ToolCall> trace = new ArrayList<>();
  List<ToolCallback> callbacks = adapter.forCaller(alice, trace);
  assertThat(json.readValue(byName(callbacks, "get_asset_info").call("{\"symbol\": \"NOPE\"}"), Map.class))
   .containsEntry("error", "Unknown asset NOPE");
  assertThat(json.readValue(byName(callbacks, "get_asset_info").call("not json"), Map.class).get("error").toString())
   .startsWith("Tool input is not a JSON object");
  assertThat(trace).hasSize(2).noneMatch(ToolCall::ok);
 }

 @Test void aSubsetForOneSubAgentAndUnknownNamesFailFast() {
  assertThat(adapter.forCaller(alice, new ArrayList<>(), List.of("get_portfolio", "get_transactions")))
   .extracting(c -> c.getToolDefinition().name()).containsExactlyInAnyOrder("get_portfolio", "get_transactions");
  assertThatThrownBy(() -> adapter.forCaller(alice, new ArrayList<>(), List.of("transfer_funds")))
   .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("transfer_funds");
 }

 private static ToolCallback byName(List<ToolCallback> callbacks, String name) {
  return callbacks.stream().filter(c -> c.getToolDefinition().name().equals(name)).findFirst().orElseThrow();
 }
}
