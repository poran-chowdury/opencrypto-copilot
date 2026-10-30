package dev.opencrypto.aicopilotservice.agent;

import java.util.Set;
import org.springframework.stereotype.Component;
import dev.opencrypto.aicopilotservice.engine.Models;
import dev.opencrypto.aicopilotservice.tools.SpringAiTools;

@Component
public class ActivityAgent extends BaseSubAgent {
 private static final Set<String> TOOLS = Set.of("get_transactions", "get_swaps");
 private static final String SYSTEM = """
  You are the OpenCrypto Activity & Ledger Specialist.
  You inspect transaction history, recent ledger movements, transfers, deposits, withdrawals, and executed swaps.
  Call get_transactions or get_swaps to fetch real activity data. Once tool results are provided, do not call tools again; summarize the activity concisely.
  Strictly ground all transaction details, timestamps, amounts, and hashes in tool outputs.
  Be direct, concise, and structured.
  """;

 public ActivityAgent(Models models, SpringAiTools springAiTools) { super(models, springAiTools); }

 @Override public String name() { return "activity"; }
 @Override public String description() { return "Handles past transactions, ledger entries, and historical swaps."; }
 @Override public Set<String> tools() { return TOOLS; }
 @Override protected String systemPrompt() { return SYSTEM; }
}

