package dev.opencrypto.aicopilotservice.tools;

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A tool the copilot may call. Serialises to the shape most LLM tool-use APIs expect:
 * {@code {"name", "description", "input_schema"}} where input_schema is a JSON Schema object.
 */
public record ToolDefinition(String name, String description, @JsonProperty("input_schema") Map<String,Object> inputSchema) {}
