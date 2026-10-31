package dev.opencrypto.aicopilotservice.tools;

import java.util.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import dev.opencrypto.aicopilotservice.mcp.McpCallerContext;

/**
 * {@link CopilotTools} as Spring AI {@link ToolCallback}s, for engines built on Spring AI.
 *
 * Callbacks are made per request and bound to that request's {@link Caller}: the model supplies
 * tool input only, never the user id or role. Every call is appended to the given trace (return it
 * as {@code toolsExecuted} so the UI shows it). Failures go back to the model as
 * {@code {"error": "..."}} rather than exceptions, so it can tell the user what went wrong.
 *
 * In Step 4, connects to domain MCP servers dynamically over streamable HTTP.
 */
@Component
public class SpringAiTools {
 private static final Logger log = LoggerFactory.getLogger(SpringAiTools.class);
 private static final TypeReference<Map<String,Object>> MAP = new TypeReference<>() {};

 private final CopilotTools tools;
 private final ObjectMapper json;
 private final List<McpSyncClient> mcpClients;

 public SpringAiTools(CopilotTools tools, ObjectMapper json) {
  this(tools, json, List.of());
 }

 @org.springframework.beans.factory.annotation.Autowired
 public SpringAiTools(CopilotTools tools, ObjectMapper json, List<McpSyncClient> mcpClients) {
  this.tools = tools;
  this.json = json;
  this.mcpClients = mcpClients != null ? mcpClients : List.of();
 }

 /** Every tool, bound to {@code caller}. */
 public List<ToolCallback> forCaller(Caller caller, List<ToolCall> trace) {
  return tools.definitions().stream().map(d -> (ToolCallback) new Bound(d, caller, trace)).toList();
 }

 /** Only the named tools, e.g. one sub-agent's own subset (step 3). Unknown names fail fast. */
 public List<ToolCallback> forCaller(Caller caller, List<ToolCall> trace, Collection<String> names) {
  List<String> unknown = names.stream().filter(n -> !tools.exists(n)).toList();
  if (!unknown.isEmpty()) throw new IllegalArgumentException("Unknown tools " + unknown + "; available: " + tools.definitions().stream().map(ToolDefinition::name).toList());
  Set<String> wanted = new HashSet<>(names);
  return forCaller(caller, trace).stream().filter(c -> wanted.contains(c.getToolDefinition().name())).toList();
 }

 private McpSyncClient findMcpClientFor(String toolName) {
  if (mcpClients.isEmpty()) return null;
  for (McpSyncClient c : mcpClients) {
   try {
    var res = c.listTools();
    if (res != null && res.tools() != null) {
     for (var t : res.tools()) {
      if (t.name().equals(toolName)) return c;
     }
    }
   } catch (Exception e) {
    log.debug("MCP client error querying tools: {}", e.getMessage());
   }
  }
  return null;
 }

 private Object extractMcpContent(List<McpSchema.Content> contents) {
  if (contents == null || contents.isEmpty()) return Map.of();
  for (var c : contents) {
   if (c instanceof McpSchema.TextContent tc) {
    try {
     return json.readValue(tc.text(), Object.class);
    } catch (Exception e) {
     return tc.text();
    }
   }
  }
  return contents.getFirst();
 }

 private final class Bound implements ToolCallback {
  private final org.springframework.ai.tool.definition.ToolDefinition definition;
  private final Caller caller;
  private final List<ToolCall> trace;

  Bound(ToolDefinition d, Caller caller, List<ToolCall> trace) {
   this.definition = DefaultToolDefinition.builder().name(d.name()).description(d.description()).inputSchema(write(d.inputSchema())).build();
   this.caller = caller;
   this.trace = trace;
  }

  @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() { return definition; }

  @Override public String call(String toolInput) {
   log.info("Executing tool callback: {} with input: {}", definition.name(), toolInput);
   McpCallerContext.set(caller);
   ToolCall call;
   try {
    Map<String,Object> input = toolInput == null || toolInput.isBlank() ? Map.of() : json.readValue(toolInput, MAP);
    McpSyncClient mcpClient = findMcpClientFor(definition.name());
    if (mcpClient != null) {
     long start = System.nanoTime();
     var res = mcpClient.callTool(new McpSchema.CallToolRequest(definition.name(), input));
     long durationMs = (System.nanoTime() - start) / 1_000_000;
     if (Boolean.TRUE.equals(res.isError())) {
      call = new ToolCall(definition.name(), input, null, "MCP tool error: " + res.content(), durationMs);
     } else {
      Object out = extractMcpContent(res.content());
      call = new ToolCall(definition.name(), input, out, null, durationMs);
     }
    } else {
     call = tools.call(caller, definition.name(), input);
    }
   } catch (JsonProcessingException e) {
    call = new ToolCall(definition.name(), Map.of("raw", toolInput), null, "Tool input is not a JSON object: " + e.getOriginalMessage(), 0);
   } catch (Exception e) {
    log.warn("MCP tool call failed, falling back to local CopilotTools: {}", e.getMessage());
    try {
     Map<String,Object> input = toolInput == null || toolInput.isBlank() ? Map.of() : json.readValue(toolInput, MAP);
     call = tools.call(caller, definition.name(), input);
    } catch (Exception ex) {
     call = new ToolCall(definition.name(), Map.of("raw", toolInput), null, "Tool failed: " + ex.getMessage(), 0);
    }
   } finally {
    McpCallerContext.clear();
   }

   synchronized (trace) { trace.add(call); }
   log.info("Tool callback {} completed (ok={}, duration={}ms)", definition.name(), call.ok(), call.durationMs());
   return write(call.ok() ? call.output() : Map.of("error", call.error()));
  }
 }

 private String write(Object value) {
  try { return json.writeValueAsString(value); }
  catch (JsonProcessingException e) { throw new IllegalStateException("Tool data is not serialisable", e); }
 }
}
