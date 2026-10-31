# 13. Foundation for the agentic system: Spring Boot 3.5, model clients, tools for Spring AI

Step 0 of the [agentic system roadmap](../ai/agentic-system.md): everything an agent needs before
anyone writes agent code.

## The problem

1. **The framework didn't fit the platform.** The agent work uses Spring AI 1.1. Both maintained
   Spring AI lines (1.0.9 and 1.1.8) are built on Spring Boot 3.5.15; the repo ran 3.4.10. Mixing them
   means running a library on a Boot version it was never tested against.
2. **There were no model clients.** `LlmSettings` held a provider name and a key, but nothing turned
   them into a client. The roadmap needs two: Claude for reasoning, and a local Ollama model for
   steps that see personal data.
3. **The tools spoke their own format.** `CopilotTools` describes tools as `{name, description,
   input_schema}` and runs them with `call(caller, name, input)`. Spring AI expects `ToolCallback`s.
   An agent author shouldn't have to write that glue, and must not be able to get the caller wrong.

## Options

| Choice | Options | Trade-off |
|---|---|---|
| Boot version | stay on 3.4 and pin an old Spring AI · upgrade only the AI service · upgrade the whole repo | an untested combination · two Boot versions sharing `platform-common` · one coordinated upgrade |
| Model clients | Spring AI auto-configuration · build the clients in our own config | one chat model per app, fails without a key · a little more code, starts without keys |
| Tool bridge | rewrite the tools as `@Tool` methods · one adapter over the existing registry | two tool registries to keep in sync · the registry stays the single source of truth |

## What we did

### Spring Boot 3.5 across the repo

- Root `pom.xml`: Spring Boot **3.5.15**, Spring Cloud **2025.0.3**, and the **Spring AI 1.1.8 BOM**.
- `opentelemetry-jdbc` moved from 2.9.0-alpha to **2.15.0-alpha**: each instrumentation release is
  built against one OpenTelemetry API version, and Boot 3.5.15 manages API 1.49.0 (2.15.0 is the
  release built on 1.49.0; 2.14.0 uses 1.48, 2.16.0 uses 1.50).
- **Gateway:** Spring Cloud 2025.0 renamed the starter (`spring-cloud-starter-gateway` →
  `spring-cloud-starter-gateway-server-webflux`) and moved every property from
  `spring.cloud.gateway.*` to `spring.cloud.gateway.server.webflux.*`. The old names still work with a
  deprecation warning, and are removed in Spring Cloud 2025.1, so we moved now.

Unknown YAML keys fail silently in Spring ([bug #17](10-bugs-and-lessons.md)): if the prefix move were
wrong, the gateway would quietly lose its upstream timeouts and CORS rules. `GatewayPropertiesTest`
loads the real `application.yml`, binds it with `Binder` exactly as Spring does, and checks the
timeouts and CORS headers land in `HttpClientProperties` and `GlobalCorsProperties`.

### Model clients

`engine/Models.java` builds both clients from configuration. Each is an `Optional`: empty until
configured, so the service still starts with no keys and `deterministic` stays the default engine.

| Client | Configured when | Built with | Notes |
|---|---|---|---|
| `reasoning()` (Claude) | `LLM_PROVIDER=anthropic` and `LLM_API_KEY` set | `AnthropicChatModel`, model `LLM_MODEL` (default `claude-opus-5-5`), `max-tokens` 16000 | a **plain** `RestClient`: our correlation id and trace headers never go to a third party |
| `local()` (Ollama) | `LOCAL_LLM_MODEL` set | `OllamaChatModel` on `LOCAL_LLM_BASE_URL` | Spring's traced `RestClient.Builder` (internal infra); pull strategy `NEVER`, so start-up never downloads GBs |

Why not Spring AI's auto-configuration: it assumes **one** chat model per application, and the
Anthropic auto-configuration fails at start-up without a key. We depend on the model modules
(`spring-ai-anthropic`, `spring-ai-ollama`, `spring-ai-client-chat`), not the Boot starters.

Shared by both clients:
- **Retries:** one quick retry on transient errors (429, 5xx). Spring AI's default retries for minutes,
  which is fine for a batch job and wrong for a chat request a user is waiting on.
- **Timeouts:** 5 s to connect, 120 s to read (adaptive thinking can take a while).
- **Observations:** both clients and the `ToolCallingManager` report to Micrometer, so model and tool
  spans reach Tempo once step 11 wires them up.
- **Cost:** `ModelPricing` holds list prices per million tokens (`copilot.pricing.models` in
  `application.yml`: Opus 5.5 $4 / $20, Sonnet 5.5 $2 / $10, Haiku 5.5 $0.10 / $0.50). An engine calls
  `estimateUsd(model, in, out)` and puts the result in `usage.estimatedCostUsd`; the UI shows it.

`/api/v1/ai/status` now reports `models.reasoning` and `models.local` (provider, model, configured,
base URL). Never the key.

**Trade-off to know about:** Spring AI 1.1's Anthropic module uses Spring's own REST client, not
Anthropic's official Java SDK. It covers messages and tool use, but newer API features (effort
settings, adaptive-thinking display, server-side refusal fallbacks) can arrive later in Spring AI than
in the official SDK. An engine that needs one of them can implement `CopilotEngine` on the official SDK
directly; everything else in the platform stays the same.

### Tools as Spring AI tool callbacks

`tools/SpringAiTools.java` adapts `CopilotTools` (still the single tool registry) into Spring AI
`ToolCallback`s:

```java
List<ToolCall> trace = new ArrayList<>();
List<ToolCallback> tools = springAiTools.forCaller(caller, trace);                       // all 11
List<ToolCallback> portfolioOnly = springAiTools.forCaller(caller, trace, List.of("get_portfolio", "simulate_price_shock"));
```

- **The caller is bound when the callbacks are created**, from request headers. A model that sends
  `{"userId": "bob"}` still gets the caller's own portfolio (`SpringAiToolsTest` proves it).
- **Every call lands in `trace`**, which the engine returns as `toolsExecuted`, so the UI shows it.
- **Failures are data:** an unknown asset or malformed JSON goes back to the model as
  `{"error": "..."}`, so it can tell the user instead of the request failing.
- **Subsets** are for step 3: each sub-agent gets only its own tools. An unknown name fails fast.

### UI: the Assistant page

`/assistant` is an agent console: the conversation on the left; on the right, how the selected answer
was produced (engine, mode, model, latency, tokens, estimated cost, each tool call with its input and
output, caveats, and the state of any proposed action). Tiles at the top show the active engine, both
model clients and the tool count, straight from `/api/v1/ai/status`. The side panel and the page share
one hook (`lib/use-chat.ts`); later steps add agents, MCP servers, masked values and policy checks to the
same inspector.

## See it yourself

```bash
mvn verify                                   # whole repo on Boot 3.5, including Testcontainers
mvn -pl services/api-gateway -am test -Dtest=GatewayPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false
curl -s localhost:8080/api/v1/ai/status      # engines, models.reasoning, models.local, tools
```

Open http://localhost:5173/assistant and ask "What is my portfolio worth?". The inspector shows the
deterministic engine's tool calls.

## Pitfalls

- **A framework upgrade is a dependency-graph upgrade.** The Boot bump also moved Testcontainers,
  Flyway, Jackson and the OpenTelemetry API; the alpha OpenTelemetry instrumentation had to follow by hand.
- **Deprecated-but-working config is a time bomb.** It works today, logs a warning nobody reads, and
  breaks at the next release train. Move it while the old name still works, and test that it binds.
- **A first connection to a freshly started Docker can be slow.** The first full run after starting
  Docker Desktop failed `MarketDataIT` because the first Redis connect took longer than the 1 s timeout
  (the cache write is best-effort, so the test saw an empty cache). It passed on its own and on the next
  full run. Not a regression, but worth knowing before blaming the upgrade.
- **A disabled button can dim its whole input group.** The axe scan of `/assistant` failed colour contrast
  (2.22:1) on the "Enter to send" hint. The shadcn `InputGroup` applies `opacity-50` when it *contains* any
  disabled control (`has-disabled:`), and the Send button was disabled while the box was empty, so the hint
  and the text you type were drawn at half opacity. The Send button now uses `aria-disabled` (the submit
  handler already ignores empty input). The side panel had the same bug; axe never saw it because the panel
  is closed during the scan.
- **`scrollIntoView` scrolls every ancestor, including the window.** Keeping the newest message in view
  with it made the page jump past its own header on a phone. Scroll the conversation's own viewport instead.
- **Concurrent Maven runs share `~/.m2`.** A second build that starts while the first is still
  downloading can read a half-written POM ("Non-readable POM … input contained no data").

## Interview questions

- How do you upgrade a framework's major-minor version across many services safely?
- Why is a deprecated configuration key more dangerous than a removed one?
- How would you prove a configuration change took effect without starting the whole app?
- Why build model clients yourself instead of using auto-configuration?
