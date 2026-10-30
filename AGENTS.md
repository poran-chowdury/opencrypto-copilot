# AI Pair Programming & Learning Guidelines

This repository is a **hands-on learning project** designed to master agentic AI systems engineering.

---

## 1. Fundamental Rule: Teach and Collaborate, Never Just Build

When working on any step of the agentic roadmap (`docs/ai/agentic-system.md`):

1. **Discuss the Problem First:**
   - Clearly explain the specific failure or limitation (e.g., why a plain model hallucinates balances, why tool overload causes wrong calls, how multi-agent supervisor patterns solve context limits).
   - Show how to reproduce the issue before solving it.

2. **Teach the Concepts & Options:**
   - Explain the architectural trade-offs (e.g., manual tool-loop vs framework-managed loops, local vs hosted models, RAG vs function calling).
   - Ensure the user understands the concepts, mechanics, and design rationale.

3. **Collaborative Implementation:**
   - Walk through the seams, code changes, and test assertions step-by-step.
   - Respect repository conventions (compact 1-space Java indentation, read-only tools, proposed actions for state changes).

4. **Always Produce a Learning Note in `docs/engineering/`:**
   - For every completed step, author a corresponding learning document in `docs/engineering/XX-<step-name>.md`.
   - Maintain the strict 6-part learning structure:
     1. **The Problem** (with reproducible baseline/failure)
     2. **Options** (trade-offs table)
     3. **What We Did** (architecture diagram, code highlights, design choices)
     4. **See It Yourself** (exact PowerShell/curl commands to test and observe)
     5. **Pitfalls** (gotchas, edge cases, and debugging lessons)
     6. **Interview Questions** (practical questions testing deep understanding)

---

## 2. Clean Code & SOLID Principles

Every line of code written in this repository must adhere to clean software design:

- **Single Responsibility Principle (SRP):**
  - Keep classes and methods laser-focused on one job.
  - Model clients (`Models`) only construct and configure clients; tool adapters (`SpringAiTools`) only bridge and trace tool execution; engines (`CopilotEngine`) handle conversation orchestration; controllers (`AiController`) only map HTTP transport.
- **Open / Closed Principle (OCP):**
  - The copilot engine architecture is open for extension, closed for modification. New engines (e.g., `LlmCopilotEngine`, `SupervisorEngine`) implement `CopilotEngine` and register by `name()`. Adding an engine never requires modifying existing engine implementations.
- **Liskov Substitution Principle (LSP):**
  - Any `CopilotEngine` implementation must fulfill the `reply(Caller, ChatRequest)` contract completely and predictably, regardless of whether it uses deterministic rules, local models, or remote Claude endpoints.
- **Interface Segregation Principle (ISP):**
  - Rely on small, intentional interfaces (`CopilotEngine`, `Retriever`, `ToolCallback`). Do not force callers or engines to depend on bloated multi-purpose interfaces.
- **Dependency Inversion Principle (DIP):**
  - High-level modules must never depend on volatile low-level details. Depend on abstractions (`ChatClient`, `Retriever`, `CopilotEngine`). Use Spring constructor injection everywhere; avoid hardcoded dependencies or static state.
- **Clean Code Mechanics:**
  - Self-documenting naming (no cryptic abbreviations).
  - Explicit error handling: Fail fast with standard RFC 7807 `ApiException` problems; never swallow exceptions or return fallback fake data on unexpected errors.
  - Immutability by default: Prefer Java `record`s for DTOs, messages, and settings. Use immutable collections (`List.of()`, `Map.of()`).

---

## 3. Strict Architectural Isolation & Loose Coupling

To prevent brittle microservices and maintain clear system boundaries:

- **Data Ownership Isolation:**
  - Enforced by PostgreSQL user roles and separate schemas (`asset`, `wallet`, `swap`, `txn`, `ai`).
  - A service **never** queries or writes to another service's tables. Cross-service data is accessed strictly via typed HTTP clients or RabbitMQ events.
- **AI Service Decoupling:**
  - `ai-copilot-service` must not compile against domain entity models or share domain JARs.
  - All interactions with domain services must go through typed, allowlisted, read-only tools (`CopilotTools`) or (in Step 4) standardized Model Context Protocol (MCP) clients.
- **State Change Decoupling (Propose, Never Execute):**
  - The AI agent never executes mutations directly. For state changes (e.g., placing swaps, transferring funds), it calls a preview tool (e.g., `get_swap_quote`) and emits a `ProposedAction`.
  - The human user explicitly reviews and confirms the action in the UI. The target domain service validates the request from scratch independently.
- **Identity Decoupling:**
  - `Caller` context (user ID, user tier/role) is injected strictly from gateway-verified request headers (`X-Demo-User`, and in Step 5 OIDC JWT claims).
  - **Never** allow the LLM or tool arguments to provide, override, or spoof caller identity.

---

## 4. Test Discipline & Test Isolation

All code must be backed by fast, isolated, and deterministic tests:

- **Fast Unit Tests (`*Test.java`):**
  - Unit tests run under Maven Surefire (`mvn test`) without needing Docker, live external APIs, or actual model network calls.
  - Model calls must be tested using fakes (such as the JDK built-in `HttpServer` as demonstrated in `LocalEngineStep1Test`) or mocked clients to guarantee repeatability and milliseconds execution.
- **Integration Tests (`*IT.java`):**
  - Run under Maven Failsafe (`mvn verify`) and use real infrastructure via Testcontainers (`@DynamicPropertySource` with dedicated schema).
  - Never use thread sleeps (`Thread.sleep`); use Awaitility with explicit conditions and timeouts.
- **Golden Evaluation Suite (`evaluation/run_eval.py`):**
  - Always run evaluations with a fixed seed (`--seed 42`) against the simulated market feed.
  - Verify number grounding (no hallucinated figures) and strict safety refusals (`OC-009` through `OC-013`).

---

## 5. Repository Style Conventions

- **Indentation:** Compact formatting with **1-space indentation** in Java classes to match the repository's surrounding style.
- **Git Commits:** Follow conventional commits (`feat(copilot): ...`, `test(ai): ...`, `docs(engineering): ...`).
