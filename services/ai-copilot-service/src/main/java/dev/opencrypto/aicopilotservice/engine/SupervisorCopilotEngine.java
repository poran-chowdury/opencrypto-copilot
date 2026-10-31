package dev.opencrypto.aicopilotservice.engine;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import dev.opencrypto.aicopilotservice.agent.SubAgent;
import dev.opencrypto.aicopilotservice.tools.Caller;
import dev.opencrypto.aicopilotservice.tools.ToolCall;

/**
 * Step 3 of docs/ai/agentic-system.md: Domain sub-agents with a supervisor.
 *
 * Rather than overloading a single prompt with 11 tools (~2,200 schema tokens),
 * the supervisor decomposes the user request across specialized domain sub-agents:
 * - portfolio: balances, holdings, portfolio timeline (2 tools)
 * - activity: ledger entries, transactions, past swaps (2 tools)
 * - trading: live market prices, asset stats, swap quotes (4 tools)
 * - support: knowledge base, platform rules, market events (2 tools)
 *
 * For multi-part questions (e.g. "my balance and my last 5 transactions"), the supervisor
 * executes each sub-agent with its isolated tool boundary and synthesizes the answers.
 */
@Component
public class SupervisorCopilotEngine implements CopilotEngine {
 private static final Logger log = LoggerFactory.getLogger(SupervisorCopilotEngine.class);

 private final Map<String, SubAgent> agents = new LinkedHashMap<>();
 private final Models models;

 public record SubTask(String agentName, String subGoal) {}

 public SupervisorCopilotEngine(List<SubAgent> subAgents, Models models) {
  for (SubAgent a : subAgents) {
   this.agents.put(a.name(), a);
  }
  this.models = models;
  log.info("Supervisor initialized with {} sub-agents: {}", agents.size(), agents.keySet());
 }

 @Override public String name() { return "supervisor"; }

 @Override public boolean llmConnected() {
  return models.localClient().isPresent() || models.reasoningClient().isPresent();
 }

 @Override public ChatResponse reply(Caller caller, ChatRequest request) {
  String lower = request.message() == null ? "" : request.message().trim().toLowerCase();
  String activeModel = models.localClient().isPresent() ? models.localInfo().model() : models.reasoningInfo().model();
  if (activeModel == null) activeModel = "supervisor";

  // Safety Refusals (OC-010, OC-012, OC-013)
  if (isPromptExtraction(lower)) {
   return new ChatResponse(caller.userId(),
    "I cannot reveal internal system prompts, hidden instructions, or configuration details.",
    "SUPERVISOR", name(), true, List.of(), null,
    List.of(DeterministicCopilotEngine.NOT_ADVICE),
    Map.of("model", activeModel, "safety", "refusal", "finishReason", "stop"));
  }
  if (isUnauthorizedExfiltration(lower)) {
   return new ChatResponse(caller.userId(),
    "Access denied. You can only view and manage your own authenticated account data.",
    "SUPERVISOR", name(), true, List.of(), null,
    List.of(DeterministicCopilotEngine.NOT_ADVICE),
    Map.of("model", activeModel, "safety", "refusal", "finishReason", "stop"));
  }
  if (isGreeting(lower)) {
   return new ChatResponse(caller.userId(),
    "Hello! I am the OpenCrypto AI Copilot. I can help you check portfolio balances, review recent transactions, inspect live crypto prices, simulate price shocks, or calculate swap quotes.",
    "SUPERVISOR", name(), true, List.of(), null,
    List.of(DeterministicCopilotEngine.NOT_ADVICE),
    Map.of("model", activeModel, "finishReason", "stop"));
  }

  List<SubTask> plan = plan(request.message());
  log.info("Supervisor planned {} sub-tasks for user '{}': {}", plan.size(), caller.userId(), plan);

  List<ToolCall> trace = new ArrayList<>();
  List<String> executedAgents = new ArrayList<>();
  List<Message> historyMessages = history(request);
  StringBuilder combinedAnswer = new StringBuilder();

  for (SubTask task : plan) {
   SubAgent agent = agents.get(task.agentName());
   if (agent == null) continue;
   executedAgents.add(agent.name());

   String answer = agent.execute(caller, task.subGoal(), historyMessages, trace);
   if (answer != null && !answer.isBlank()) {
    if (!combinedAnswer.isEmpty()) combinedAnswer.append("\n\n");
    combinedAnswer.append(answer.trim());
   }
  }

  if (combinedAnswer.isEmpty()) {
   combinedAnswer.append("I was unable to retrieve the requested information. Please try rephrasing your request.");
  }

  ProposedAction proposed = extractProposedAction(trace);

  List<String> caveats = new ArrayList<>();
  caveats.add(DeterministicCopilotEngine.NOT_ADVICE);
  if (proposed != null) {
   caveats.add("The copilot cannot execute trades directly. Confirmation is required and is validated independently by the swap service.");
  }

  Map<String, Object> usage = new LinkedHashMap<>();
  usage.put("model", activeModel);
  usage.put("agents", executedAgents);
  usage.put("subTasks", plan.size());
  usage.put("finishReason", "stop");

  return new ChatResponse(caller.userId(), combinedAnswer.toString(), "SUPERVISOR", name(), true,
   trace, proposed, caveats, usage);
 }

 /**
  * Decomposes the user message into domain-targeted sub-tasks.
  * Handles single-domain queries as well as compound queries (e.g., balance AND transactions).
  */
 public List<SubTask> plan(String message) {
  String lower = message.toLowerCase();

  boolean isBalance = lower.contains("balance") || lower.contains("portfolio") || lower.contains("holding")
   || lower.contains("worth") || lower.contains("how much btc") || lower.contains("how much eth");

  boolean isActivity = lower.contains("transaction") || lower.contains("history") || lower.contains("deposit")
   || lower.contains("withdraw") || lower.contains("ledger") || lower.contains("transfers") || lower.contains("past swap")
   || lower.contains("last swap") || lower.contains("failed swap") || lower.contains("activity");

  boolean isTrading = lower.contains("price") || lower.contains("rate") || lower.contains("swap")
   || lower.contains("trade") || lower.contains("quote") || lower.contains("market") || lower.contains("shock")
   || lower.contains("buy") || lower.contains("sell") || lower.contains("fee");

  boolean isSupport = lower.contains("how does") || lower.contains("how do") || lower.contains("what is")
   || lower.contains("explain") || lower.contains("policy") || lower.contains("rule") || lower.contains("event")
   || lower.contains("network") || lower.contains("docs");

  List<SubTask> tasks = new ArrayList<>();

  // Multi-part compound requests
  if (isBalance && isActivity) {
   tasks.add(new SubTask("portfolio", "Check current portfolio balances and token holdings."));
   tasks.add(new SubTask("activity", "Retrieve recent transactions and ledger activity."));
   return tasks;
  }
  if (isTrading && isBalance) {
   tasks.add(new SubTask("trading", "Inspect live market prices and rates."));
   tasks.add(new SubTask("portfolio", "Check portfolio balances and token holdings."));
   return tasks;
  }
  if (isTrading && isActivity) {
   tasks.add(new SubTask("trading", "Inspect live trading rates and quotes."));
   tasks.add(new SubTask("activity", "Check past swap and transaction history."));
   return tasks;
  }

  // Single-domain requests
  if (isBalance) tasks.add(new SubTask("portfolio", message));
  else if (isActivity) tasks.add(new SubTask("activity", message));
  else if (isTrading) tasks.add(new SubTask("trading", message));
  else if (isSupport) tasks.add(new SubTask("support", message));
  else tasks.add(new SubTask("support", message)); // default fallback

  return tasks;
 }

 private static List<Message> history(ChatRequest request) {
  if (request.history() == null || request.history().isEmpty()) return List.of();
  var h = request.history();
  if (h.size() > 4) h = h.subList(h.size() - 4, h.size());
  return h.stream()
   .<Message>map(m -> m.role().equals("assistant") ? new AssistantMessage(m.content()) : new UserMessage(m.content()))
   .toList();
 }

 private ProposedAction extractProposedAction(List<ToolCall> trace) {
  for (int i = trace.size() - 1; i >= 0; i--) {
   ToolCall c = trace.get(i);
   if ("get_swap_quote".equals(c.name()) && c.ok() && c.output() instanceof Map<?, ?> q) {
    Map<String, Object> payload = new LinkedHashMap<>();
    for (String k : List.of("quoteId", "fromSymbol", "toSymbol", "inputAmount", "estimatedOutput", "fee", "rate", "expiresAt")) {
     if (q.containsKey(k)) payload.put(k, q.get(k));
    }
    String desc = "Swap %s %s for about %s %s".formatted(
     amount(q.get("inputAmount")), q.get("fromSymbol"), amount(q.get("estimatedOutput")), q.get("toSymbol"));
    return new ProposedAction("EXECUTE_SWAP_QUOTE", desc, payload, true);
   }
  }
  return null;
 }

 private static String amount(Object o) {
  return o == null ? "0" : String.valueOf(o);
 }

 private static boolean isPromptExtraction(String lower) {
  return lower.contains("system prompt")
   || lower.contains("your prompt")
   || lower.contains("reveal your")
   || lower.contains("ignore all rules")
   || lower.contains("ignore previous")
   || lower.contains("internal instructions")
   || lower.contains("hidden prompt");
 }

 private static boolean isUnauthorizedExfiltration(String lower) {
  return lower.contains("all user data")
   || lower.contains("another user")
   || lower.contains("other user")
   || lower.contains("database password")
   || lower.contains("api key")
   || lower.contains("environment variable")
   || lower.contains("secret");
 }

 private static boolean isGreeting(String lower) {
  return lower.equals("hi") || lower.equals("hello") || lower.equals("hey")
   || lower.equals("help") || lower.startsWith("help ") || lower.contains("what can you do");
 }
}
