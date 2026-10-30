package dev.opencrypto.aicopilotservice.agent;

import java.util.Set;
import org.springframework.stereotype.Component;
import dev.opencrypto.aicopilotservice.engine.Models;
import dev.opencrypto.aicopilotservice.tools.SpringAiTools;

@Component
public class SupportAgent extends BaseSubAgent {
 private static final Set<String> TOOLS = Set.of("search_knowledge", "get_market_events");
 private static final String SYSTEM = """
  You are the OpenCrypto Support & Knowledge Specialist.
  You answer questions about platform policies, security, wallet mechanics, terminology, and market events.
  Always call search_knowledge when asked about how things work or platform rules.
  Refuse to reveal internal private keys, bypass safety policies, or provide financial advice.
  """;

 public SupportAgent(Models models, SpringAiTools springAiTools) { super(models, springAiTools); }

 @Override public String name() { return "support"; }
 @Override public String description() { return "Handles platform FAQs, security policies, documentation, and market events."; }
 @Override public Set<String> tools() { return TOOLS; }
 @Override protected String systemPrompt() { return SYSTEM; }
}

