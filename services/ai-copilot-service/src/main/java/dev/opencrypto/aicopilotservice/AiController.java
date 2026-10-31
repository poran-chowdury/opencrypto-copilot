package dev.opencrypto.aicopilotservice;

import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import io.modelcontextprotocol.client.McpSyncClient;
import dev.opencrypto.aicopilotservice.engine.CopilotEngine;
import dev.opencrypto.aicopilotservice.engine.CopilotEngine.ChatRequest;
import dev.opencrypto.aicopilotservice.engine.CopilotEngine.ChatResponse;
import dev.opencrypto.aicopilotservice.engine.LlmSettings;
import dev.opencrypto.aicopilotservice.engine.Models;
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
 private final Models models;
 private final List<McpSyncClient> mcpClients;

 public AiController(List<CopilotEngine> engines, @Value("${copilot.engine:deterministic}") String defaultEngine,
   CopilotTools tools, Retrievers retrievers, LlmSettings llm, Models models,
   List<McpSyncClient> mcpClients) {
  engines.forEach(e -> {
   if (this.engines.putIfAbsent(e.name(), e) != null) throw new IllegalStateException("Duplicate copilot engine name: " + e.name());
  });
  if (!this.engines.containsKey(defaultEngine))
   throw new IllegalStateException("copilot.engine=" + defaultEngine + " but available engines are " + this.engines.keySet());
  this.defaultEngine = defaultEngine;
  this.tools = tools;
  this.retrievers = retrievers;
  this.llm = llm;
  this.models = models;
  this.mcpClients = mcpClients != null ? mcpClients : List.of();
 }

 @PostMapping("/chat") public ChatResponse chat(@Valid @RequestBody ChatRequest r,
   @RequestHeader(value="X-Auth-User", required=false) String authUser,
   @RequestHeader(value="X-Demo-User", defaultValue="local-demo-user") String user,
   @RequestHeader(value="X-Auth-Tier", required=false) String authTier,
   @RequestHeader(value="X-Demo-Role", defaultValue="user") String role) {
  String resolvedUser = (authUser != null && !authUser.isBlank()) ? authUser : user;
  String resolvedRole = (authTier != null && !authTier.isBlank()) ? authTier : role;
  return engine(r.engine()).reply(new Caller(resolvedUser, resolvedRole), r);
 }

 @GetMapping("/status") public Map<String,Object> status() {
  Map<String,Object> m = new LinkedHashMap<>();
  m.put("engine", defaultEngine);
  m.put("llmConnected", engines.get(defaultEngine).llmConnected());
  m.put("engines", engines.values().stream().map(e -> Map.of("name", e.name(), "llmConnected", e.llmConnected())).toList());
  m.put("llm", Map.of("provider", Objects.toString(llm.provider(), ""), "model", Objects.toString(llm.model(), ""), "configured", llm.configured()));
  m.put("models", Map.of("reasoning", models.reasoningInfo(), "local", models.localInfo()));
  m.put("retrievers", retrievers.names());
  m.put("defaultRetriever", retrievers.defaultName());
  m.put("tools", tools.definitions().stream().map(ToolDefinition::name).toList());

  List<Map<String,Object>> mcpList = new ArrayList<>();
  for (McpSyncClient client : mcpClients) {
   try {
    boolean init = client.isInitialized();
    if (!init) {
     try { client.initialize(); init = client.isInitialized(); } catch (Exception ignored) {}
    }
    var info = init ? client.getServerInfo() : null;
    var list = init ? client.listTools() : null;
    List<String> toolNames = list != null && list.tools() != null
      ? list.tools().stream().map(io.modelcontextprotocol.spec.McpSchema.Tool::name).toList()
      : List.of();
    Map<String,Object> serverMap = new LinkedHashMap<>();
    serverMap.put("name", info != null ? info.name() : "wallet-mcp");
    serverMap.put("version", info != null ? info.version() : "0.1.0");
    serverMap.put("connected", init);
    serverMap.put("tools", toolNames);
    mcpList.add(serverMap);
   } catch (Exception e) {
    mcpList.add(Map.of("connected", false, "error", Objects.toString(e.getMessage(), "unknown")));
   }
  }
  m.put("mcpServers", mcpList);
  return m;
 }

 /** Tool definitions in LLM tool-use format: name, description, input_schema. */
 @GetMapping("/tools") public List<ToolDefinition> toolDefinitions() { return tools.definitions(); }

 /** Invoke one tool directly. Useful for exploring what a model would see. */
 @PostMapping("/tools/{name}") public ToolCall invoke(@PathVariable String name, @RequestBody(required=false) Map<String,Object> input,
   @RequestHeader(value="X-Auth-User", required=false) String authUser,
   @RequestHeader(value="X-Demo-User", defaultValue="local-demo-user") String user,
   @RequestHeader(value="X-Auth-Tier", required=false) String authTier,
   @RequestHeader(value="X-Demo-Role", defaultValue="user") String role) {
  if (!tools.exists(name)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown tool: " + name);
  String resolvedUser = (authUser != null && !authUser.isBlank()) ? authUser : user;
  String resolvedRole = (authTier != null && !authTier.isBlank()) ? authTier : role;
  return tools.call(new Caller(resolvedUser, resolvedRole), name, input);
 }

 private CopilotEngine engine(String name) {
  if (name == null || name.isBlank()) return engines.get(defaultEngine);
  CopilotEngine e = engines.get(name);
  if (e == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown engine '" + name + "'. Available: " + engines.keySet());
  return e;
 }
}
