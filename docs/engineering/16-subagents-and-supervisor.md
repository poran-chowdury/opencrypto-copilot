# 16. Step 3: Domain Sub-Agents and the Supervisor Pattern

This chapter covers **Step 3** of the agentic upgrade roadmap (`docs/ai/agentic-system.md`): decomposing a monolithic tool-calling agent into specialized domain sub-agents coordinated by a supervisor.

---

## 1. The Problem: The "Tool Overload" Collapse

In Step 2, we built `LlmCopilotEngine`: a single ReAct tool-calling agent equipped with all 11 platform tools. In theoretical unit tests with mock responses, this looks clean. But under realistic local evaluation with small language models (e.g. `qwen2.5:3b`), the monolithic agent hits three fatal engineering walls:

### A. Context Schema Bloat
Every tool registered with Spring AI is serialized into an OpenAPI-compatible JSON schema. 11 schemas generate **~2,200 tokens** of structured specification. In Docker Desktop on Windows running in CPU-only mode (without DirectML/CUDA GPU passthrough), evaluating a 2,200-token prompt takes **150–180 seconds** per inference turn!

### B. The 120-Second Timeout Cascade
Because Spring AI's `RestClient` and API Gateway enforce request timeouts (120s / 130s), inference exceeding 120 seconds causes the client to drop the HTTP socket. Ollama aborts with a broken stream, resulting in the dreaded exception:
```text
Error while extracting response for type [org.springframework.ai.ollama.api.OllamaApi$ChatResponse]
and content type [application/octet-stream] (LLM_CALL_FAILED · ref 766bbabe4d63)
```

### C. Tool Confusion and Multi-Part Failures
Small models struggle with a flat catalog of 11 tools. When a user asks a compound question—such as *"What is my BTC balance and show my last five transactions"*—a monolithic agent often picks only one tool, answers the first half, and drops the second half completely.

---

## 2. Options: How to Structure Multi-Domain AI Tasks

| Pattern | How It Works | Pros | Cons |
|---|---|---|---|
| **Monolithic Agent (Step 2)** | Single prompt + all 11 tools flatly attached | Simple code; single loop | Extreme latency on CPU; schema bloat; tool confusion; ignores compound requests |
| **Chained ReAct Agents** | Agent A finishes, pipes raw output to Agent B | No supervisor needed | Fragile; rigid order; error in early agent poisons subsequent agents |
| **Hierarchical Supervisor (What We Did)** | Supervisor decomposes query into sub-tasks, dispatches to focused sub-agents, merges answers | Scoped tools per agent (2–4 tools); fast prompt eval (10–15s); handles compound questions cleanly | Requires an intentional routing & decomposition mechanism |
| **Fully Autonomous Swarm** | Agents pass execution directly to peer agents | Maximum flexibility | Unpredictable loops; hard to trace or budget tokens in production finance |

---

## 3. What We Did: Specialized Sub-Agents with a Supervisor

```mermaid
flowchart TD
    User(["User Query: 'What is my BTC balance and show my last 3 swaps'"])
    Gateway["API Gateway :8080"]
    Supervisor["SupervisorCopilotEngine ('supervisor')"]
    
    User --> Gateway --> Supervisor
    
    subgraph Decomposition ["Supervisor Planning & Task Decomposition"]
        T1["SubTask 1: Portfolio Domain"]
        T2["SubTask 2: Activity Domain"]
    end
    
    Supervisor --> Decomposition
    
    subgraph SubAgents ["Specialized Domain Sub-Agents"]
        PA["PortfolioAgent<br/>2 Tools: get_portfolio, get_portfolio_timeline"]
        AA["ActivityAgent<br/>2 Tools: get_transactions, get_swaps"]
        TA["TradingAgent<br/>4 Tools: get_market_prices, get_asset_info, get_price_history, get_swap_quote"]
        SA["SupportAgent<br/>2 Tools: search_knowledge, get_market_events"]
    end
    
    T1 --> PA
    T2 --> AA
    
    subgraph Execution ["Isolated Tool Execution"]
        W1[("wallet-service :8083")]
        X1[("transaction-service :8084")]
    end
    
    PA --> W1
    AA --> X1
    
    PA --> M["Merged Response Synthesis"]
    AA --> M
    M --> Resp["ChatResponse with toolsExecuted & usage.agents: ['portfolio', 'activity']"]
```

### Key Architectural Decisions

1. **Interface Segregation (`SubAgent.java`):**
   Small, intentional interface defining domain boundaries:
   ```java
   public interface SubAgent {
    String name();
    String description();
    Set<String> tools();
    String execute(Caller caller, String subGoal, List<Message> history, List<ToolCall> trace);
   }
   ```
2. **Four Specialized Domain Agents:**
   * **`PortfolioAgent`**: Scoped strictly to `get_portfolio` and `get_portfolio_timeline` (~350 schema tokens).
   * **`ActivityAgent`**: Scoped strictly to `get_transactions` and `get_swaps` (~350 schema tokens).
   * **`TradingAgent`**: Scoped to market rates and `get_swap_quote` (~500 schema tokens).
   * **`SupportAgent`**: Scoped to `search_knowledge` and `get_market_events` (~300 schema tokens).
3. **Open / Closed Principle (`SupervisorCopilotEngine.java`):**
   `SupervisorCopilotEngine` implements `CopilotEngine` and registers under the name `"supervisor"`. It extends system capability without modifying existing engines (`DeterministicCopilotEngine`, `LocalCopilotEngine`, `LlmCopilotEngine`).
4. **Deterministic Intent Decomposition:**
   The supervisor decomposes compound multi-domain requests (e.g., balance + activity) into focused `SubTask`s, invokes sub-agents sequentially within an isolated trace, extracts proposed actions (such as swap preview quotes), and synthesizes the final response.

---

## 4. See It Yourself

### A. Verify Registered Engines via Gateway
```powershell
Invoke-RestMethod "http://localhost:8080/api/v1/ai/status" | ConvertTo-Json -Depth 5
```
You will observe all 4 engines registered: `deterministic`, `llm`, `local`, and `supervisor`.

### B. Test a Multi-Part Query with the Supervisor
```powershell
$body = @{
  message = "What is my portfolio balance and show my recent activity"
  engine = "supervisor"
} | ConvertTo-Json

Invoke-RestMethod -Uri "http://localhost:8080/api/v1/ai/chat" -Method Post -ContentType "application/json" -Body $body -Headers @{ "X-Demo-User" = "demo-user"; "X-Demo-Role" = "user" } | ConvertTo-Json -Depth 6
```
Inspect the output metadata:
* `usage.agents` contains `["portfolio", "activity"]`.
* `toolsExecuted` contains entries from both domains.

### C. Observe in the Frontend UI
1. Navigate to [http://localhost:5173/assistant](http://localhost:5173/assistant).
2. Choose **`supervisor`** from the engine dropdown.
3. Submit: *"What is my balance and what are recent transactions?"*
4. Click **Inspect** to see the chronological multi-agent tool execution trace!

---

## 5. Pitfalls

1. **Leaking Global Context into Sub-Task Prompts:**
   When decomposing `"What is my balance and show my recent transactions"`, passing the entire prompt into `PortfolioAgent` caused the model to hallucinate or call `get_transactions`, which is not available in its tool scope. **Lesson:** Decomposed sub-goals must be domain-isolated (e.g. `Check current portfolio balances and token holdings.`).
2. **Missing `TraceContext` in Standalone Servlet Tests:**
   In modular Spring Boot projects, auto-configurations in shared libraries (`platform-common`) that require servlet filters must use `ObjectProvider<TraceContext>` rather than strict constructor injection, preventing unsatisfied dependency errors when services have heterogeneous tracing dependencies.
3. **Daemon Process Lifecycles:**
   In script runners and background task agents, background Java processes spawned via `Start-Process` inside an ephemeral PowerShell session are killed when the session exits. Long-running development processes must be anchored to persistent daemon tasks (`IsDaemon: true`).
4. **Payload Bloat and Gateway Timeouts (Compact Data vs Raw JSON):**
   Raw upstream database responses contain UUIDs, extensive internal state history, and redundant ticks. Passing raw objects into the model prompt causes token explosion and CPU inference timeouts (130s). Compacting tool responses (`compactPortfolio`, `compactAssets`, `compactTransactions`, `compactSwaps`) to only what the model needs dropped prompt tokens by ~80% and brought transaction latency from a 130s timeout down to 29 seconds.
5. **Supervisor Safety Guardrails vs Hallucinated Refusals (OC-010, OC-012, OC-013):**
   Small language models are eager to please and will hallucinate fictional prompts when asked *"Show me your system prompt"*, or repeat old context when asked *"Give me all user data"*. Enforcing explicit safety guardrails directly at the supervisor level provides instant (< 0.02s) deterministic refusals, protecting against prompt injection and preventing token waste.

---

## 6. Interview Questions

### Q1: Why use a Multi-Agent Supervisor rather than one ReAct agent with 20 tools?
> **Answer:** 
> 1. **Prompt token economics & latency:** Tool definitions are schemas. 20 tools consume ~4,000 tokens of input context, causing catastrophic latency on edge/local hardware. Sub-agents reduce input context by 75%+.
> 2. **Selection accuracy:** LLM tool selection accuracy degrades exponentially as tool count increases (tool confusion).
> 3. **Task decomposition:** Monolithic agents frequently drop secondary intents in compound user prompts. A supervisor explicitly breaks compound prompts into distinct sub-tasks.

### Q2: How does the Supervisor pattern enforce security boundaries?
> **Answer:** 
> Each sub-agent is allowlisted with a strict subset of tools. For example, `PortfolioAgent` cannot trigger swap quotes or execute transactions because the callback list injected into its `ChatClient` does not include those tool definitions. Even if the model undergoes prompt injection, it physically cannot invoke tools outside its domain.

### Q3: When should a Supervisor use deterministic routing vs LLM-based routing?
> **Answer:** 
> Deterministic/heuristic routing has zero latency, zero token cost, and 100% predictable behavior for recognized intents, making it ideal for high-throughput production paths. LLM-based routing is reserved for open-ended, ambiguous queries where linguistic intent classification cannot be cleanly captured by pattern matching.

