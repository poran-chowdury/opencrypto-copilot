package dev.opencrypto.aicopilotservice.agent;

import java.util.List;
import java.util.Set;
import org.springframework.ai.chat.messages.Message;
import dev.opencrypto.aicopilotservice.tools.Caller;
import dev.opencrypto.aicopilotservice.tools.ToolCall;

/**
 * Step 3 of docs/ai/agentic-system.md: A specialized domain sub-agent.
 *
 * Each sub-agent is laser-focused on a single domain boundary (SRP).
 * It receives only its own allowlisted tools and a domain-specific persona prompt,
 * drastically reducing prompt token length and eliminating tool overload.
 */
public interface SubAgent {
 String name();
 String description();
 Set<String> tools();
 String execute(Caller caller, String subGoal, List<Message> history, List<ToolCall> trace);
}

