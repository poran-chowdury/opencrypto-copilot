package dev.opencrypto.aicopilotservice.agent;

import java.util.Set;
import org.springframework.stereotype.Component;
import dev.opencrypto.aicopilotservice.engine.Models;
import dev.opencrypto.aicopilotservice.tools.SpringAiTools;

@Component
public class PortfolioAgent extends BaseSubAgent {
 private static final Set<String> TOOLS = Set.of("get_portfolio", "get_portfolio_timeline");
 private static final String SYSTEM = """
  You are the OpenCrypto Portfolio Specialist.
  Inspect current asset balances and portfolio valuation.
  Call get_portfolio to check holdings. Once tool results are provided, do not call tools again; summarize balances directly.
  Strictly ground all amounts in tool outputs. Never invent balances. Keep responses concise.
  """;

 public PortfolioAgent(Models models, SpringAiTools springAiTools) { super(models, springAiTools); }

 @Override public String name() { return "portfolio"; }
 @Override public String description() { return "Handles current asset balances, portfolio valuation, and timeline performance."; }
 @Override public Set<String> tools() { return TOOLS; }
 @Override protected String systemPrompt() { return SYSTEM; }
}

