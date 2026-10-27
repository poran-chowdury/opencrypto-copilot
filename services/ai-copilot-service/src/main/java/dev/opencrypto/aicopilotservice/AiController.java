package dev.opencrypto.aicopilotservice;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import dev.opencrypto.aicopilotservice.engine.CopilotEngine;
import dev.opencrypto.aicopilotservice.engine.CopilotEngine.ChatRequest;
import dev.opencrypto.aicopilotservice.engine.CopilotEngine.ChatResponse;
import dev.opencrypto.aicopilotservice.engine.LlmSettings;
import dev.opencrypto.aicopilotservice.knowledge.Retrievers;
import dev.opencrypto.aicopilotservice.tools.Caller;
import dev.opencrypto.aicopilotservice.tools.CopilotTools;
import dev.opencrypto.aicopilotservice.tools.ToolCall;
import dev.opencrypto.aicopilotservice.tools.ToolDefinition;

@RestController @RequestMapping("/api/v1/ai")
public class AiController {
 private final Map<String, CopilotEngine> engines = new LinkedHashMap<>();
 private final String defaultEngine;
 private final CopilotTools tools;
 private final Retrievers retrievers;
 private final LlmSettings llm;

 public AiController(List<CopilotEngine> engines, @Value("${copilot.engine:deterministic}") String defaultEngine,
   CopilotTools tools, Retrievers retrievers, LlmSettings llm) {
  engines.forEach(e -> {
   if (this.engines.putIfAbsent(e.name(), e) != null) throw new IllegalStateException("Duplicate copilot engine name: " + e.name());
  });
  if (!this.engines.containsKey(defaultEngine))
   throw new IllegalStateException("copilot.engine=" + defaultEngine + " but available engines are " + this.engines.keySet());
  this.defaultEngine = defaultEngine;
  this.tools = tools;
  this.retrievers = retrievers;
  this.llm = llm;
 }

 @PostMapping("/chat") public ChatResponse chat(@Valid @RequestBody ChatRequest r,
   @RequestHeader(value="X-Demo-User",defaultValue="local-demo-user") String user,
   @RequestHeader(value="X-Demo-Role",defaultValue="user") String role) {
  return engine(r.engine()).reply(new Caller(user, role), r);
 }

 @GetMapping("/status") public Map<String,Object> status() {
  Map<String,Object> m = new LinkedHashMap<>();
  m.put("engine", defaultEngine);
  m.put("llmConnected", engines.get(defaultEngine).llmConnected());
  m.put("engines", engines.values().stream().map(e -> Map.of("name", e.name(), "llmConnected", e.llmConnected())).toList());
  m.put("llm", Map.of("provider", Objects.toString(llm.provider(), ""), "model", Objects.toString(llm.model(), ""), "configured", llm.configured()));
  m.put("retrievers", retrievers.names());
  m.put("defaultRetriever", retrievers.defaultName());
  m.put("tools", tools.definitions().stream().map(ToolDefinition::name).toList());
  return m;
 }

 /** Tool definitions in LLM tool-use format: name, description, input_schema. */
 @GetMapping("/tools") public List<ToolDefinition> toolDefinitions() { return tools.definitions(); }

 /** Invoke one tool directly. Useful for exploring what a model would see. */
 @PostMapping("/tools/{name}") public ToolCall invoke(@PathVariable String name, @RequestBody(required=false) Map<String,Object> input,
   @RequestHeader(value="X-Demo-User",defaultValue="local-demo-user") String user,
   @RequestHeader(value="X-Demo-Role",defaultValue="user") String role) {
  if (!tools.exists(name)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown tool: " + name);
  return tools.call(new Caller(user, role), name, input);
 }

 private CopilotEngine engine(String name) {
  if (name == null || name.isBlank()) return engines.get(defaultEngine);
  CopilotEngine e = engines.get(name);
  if (e == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown engine '" + name + "'. Available: " + engines.keySet());
  return e;
 }
}
