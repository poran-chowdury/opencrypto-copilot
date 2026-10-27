package dev.opencrypto.aicopilotservice.tools;

import java.util.Map;

/** Trace of one tool invocation, returned to the client so every answer is auditable. */
public record ToolCall(String name, Map<String,Object> input, Object output, String error, long durationMs) {
 public boolean ok() { return error == null; }
}
