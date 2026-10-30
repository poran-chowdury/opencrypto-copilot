package dev.opencrypto.aicopilotservice.tools;

import java.util.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.stereotype.Component;

/**
 * {@link CopilotTools} as Spring AI {@link ToolCallback}s, for engines built on Spring AI.
 *
 * Callbacks are made per request and bound to that request's {@link Caller}: the model supplies
 * tool input only, never the user id or role. Every call is appended to the given trace (return it
 * as {@code toolsExecuted} so the UI shows it). Failures go back to the model as
 * {@code {"error": "..."}} rather than exceptions, so it can tell the user what went wrong.
 */
@Component
public class SpringAiTools {
 private static final Logger log = LoggerFactory.getLogger(SpringAiTools.class);
 private static final TypeReference<Map<String,Object>> MAP = new TypeReference<>() {};

 private final CopilotTools tools;
 private final ObjectMapper json;

 public SpringAiTools(CopilotTools tools, ObjectMapper json) { this.tools = tools; this.json = json; }

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
   ToolCall call;
   try {
    Map<String,Object> input = toolInput == null || toolInput.isBlank() ? Map.of() : json.readValue(toolInput, MAP);
    call = tools.call(caller, definition.name(), input);
   } catch (JsonProcessingException e) {
    call = new ToolCall(definition.name(), Map.of("raw", toolInput), null, "Tool input is not a JSON object: " + e.getOriginalMessage(), 0);
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
