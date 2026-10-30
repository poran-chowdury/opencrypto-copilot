# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A crypto portfolio/trading platform built as production-style microservices (Java 21, Spring Boot 3.5,
Spring Cloud 2025.0 Gateway, Spring AI 1.1, Postgres, Redis, RabbitMQ) with a React 19 frontend. Balances and trades are
simulated. It is also a **learning project**: every engineering change gets a learning note in
`docs/engineering/` (shape: problem → options → what we did → see it yourself → pitfalls → interview
questions). Phase 1 (the product) is done; phase 2 is the AI copilot, now planned as a production-style
agentic system in `docs/ai/agentic-system.md` (12 steps: tools → sub-agents → MCP → auth → tiers → memory
→ PII → policies → evals → observability → edge). **Split:** the platform side (model clients, MCP servers,
auth, memory store, PII redaction, policy plumbing, tracing, eval tooling, and all UI) is built for the owner;
the owner writes the agent loop, supervisor/sub-agents and prompts. Don't implement agent reasoning or
orchestration unprompted: provide seams, tests that define "done", and guides. Starter kits: `docs/ai/projects.md`.

## Commands

Backend (Maven multi-module from the root `pom.xml`):

```bash
mvn test                        # unit tests only (*Test, Surefire), no Docker
mvn verify                      # + *IT integration tests (Failsafe, Testcontainers: needs Docker)
mvn clean verify                # what CI runs; run before a PR
mvn -B clean package -DskipTests
# one unit test class (-am builds platform-common too, so allow modules with no matching test)
mvn -pl services/wallet-service -am test -Dtest=LedgerRulesTest -Dsurefire.failIfNoSpecifiedTests=false
# one integration test (skip unit tests with a non-matching pattern)
mvn -pl services/wallet-service -am verify -Dtest=none -Dit.test=LedgerIT \
    -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
```

Naming decides where a test runs: `*IT.java` is excluded from Surefire and runs only under `verify`.

Local stack:

```bash
scripts/run-local.sh            # Windows: .\scripts\run-local.ps1. Infra in Docker, 7 services on the host
                                # (jars copied to .run/jars/, logs in .run/logs/). SKIP_BUILD=1 / -SkipBuild to restart only
scripts/stop-local.sh           # add --infra / -Infra to stop containers too
docker compose up --build -d    # everything in Docker (cp .env.example .env first)
docker compose -f docker-compose.yml -f docker-compose.release.yml up -d   # prebuilt GHCR images
docker compose config -q        # CI validates both compose files
```

Frontend (`cd frontend`):

```bash
npm run dev                     # :5173, proxies /api to the gateway on :8080 (VITE_API_TARGET overrides)
npm run build                   # tsc -b && vite build. This is the type-check
npm run a11y                    # Playwright + axe; needs the UI running (and backend for real pages)
npx playwright test -g "seeded lab"   # the backend-free subset CI runs against `vite preview`
npm run docs:diagrams           # renders every Mermaid diagram in docs/ and fails on syntax errors
```

Other: `python evaluation/run_eval.py --seed 42 --label baseline` and
`python evaluation/retrieval_eval.py --retriever keyword` (stdlib only, talk to the gateway);
`scripts/load-test.sh` (k6); `make dashboard` regenerates Grafana dashboard JSON from Python.

Host ports are deliberately non-default: Postgres **5433**, Redis **6380**, RabbitMQ **5673** (UI 15673).
Services: gateway 8080, identity 8081, asset 8082, wallet 8083, transaction 8084, swap 8085, ai-copilot 8086.
`MARKET_PROVIDER=simulated` (+ `MARKET_SEED=42`) runs offline with a reproducible synthetic market.

## Architecture

**Data ownership is enforced by Postgres, not convention.** One Postgres instance, one schema + login
role per service (`asset`, `wallet`, `swap`, `txn`, created by `infra/postgres/init/001-service-schemas.sh`).
A service cannot read another's tables. Each owns its tables via Flyway in `src/main/resources/db/migration`.
Cross-service data comes over HTTP (with Resilience4j timeouts/retries/circuit breakers) or from events.

**Sync for queries, async for workflows.** Reads use HTTP (e.g. wallet → asset for prices). Money-moving
workflows go over RabbitMQ: swap-service orchestrates a saga (CREATED → PENDING → COMPLETED/FAILED)
by sending `wallet.cmd.*` commands; wallet-service replies with `wallet.evt.*`. transaction-service
gets no HTTP writes; it projects events into the activity read model. All messaging goes through the
topic exchange `opencrypto.events`, and every queue has a `<queue>.dlq`. Routing keys and payloads are in
`docs/architecture/messaging.md`.

**Outbox/inbox from `libs/platform-common`.** Publishers call `OutboxWriter.append(...)` inside the
business transaction (`Propagation.MANDATORY`, so it fails outside one); `OutboxPublisher` ships rows on its
own dedicated thread. Consumers call `InboxGuard.firstDelivery(messageId, consumer)` at the start of the
listener transaction. Delivery is at-least-once and effects are exactly-once. platform-common is a
Spring Boot auto-configuration (correlation IDs = W3C trace id, RFC 7807 `ProblemDetailsAdvice`/`ApiException`,
`DemoUser`, tracing incl. per-SQL spans), enabled per service with `platform.messaging.enabled`. It also
ships SQL into each service's Flyway via `classpath:db/platform`.

**Message contracts are copied, not shared.** `WalletMessages` / `SwapMessages` live in each service.
There is no shared domain jar. Changes are additive only (new optional fields); a breaking change needs a new
`type` consumed in parallel.

**Ledger invariants live in the database** (`wallet-service/.../V1__ledger_schema.sql`): entries balance to
zero per asset (deferred constraint trigger), user balances can't go negative (CHECK), and journal/posting/audit
rows are immutable (triggers). Writes use row locks (accounts locked in id order) and idempotency keys.

**Gateway is the only public door.** It routes `/api/v1/**`, applies Redis token-bucket rate limits
(read/write/ai policies), never routes `/internal/**` or `/actuator/**` (except per-service health), and allows
CORS only for the headers in its `application.yml`. Users are identified by the `X-Demo-User` header. There is no
JWT auth, and that is intentional.

**AI copilot** (`ai-copilot-service`): `CopilotEngine` implementations are collected by name; `copilot.engine`
(`COPILOT_ENGINE`) picks the default and a request can choose another with `"engine"`. Tools in `tools/` are
typed and read-only over the platform APIs; `CopilotTools` is the single registry and `SpringAiTools` adapts it
to Spring AI `ToolCallback`s with the `Caller` bound per request (never taken from model input). `engine/Models`
builds the model clients itself (Claude via `LLM_PROVIDER=anthropic` + `LLM_API_KEY`, default `claude-opus-5-5`;
local Ollama via `LOCAL_LLM_MODEL`); both are `Optional` so the service starts without keys. Use the Spring AI
model modules, not the Boot starters: their auto-configuration allows one chat model and fails without a key.
Knowledge retrieval (`knowledge/`, a `Retriever` interface with a keyword/BM25 impl) indexes the `knowledge/`
corpus. Keep `DeterministicCopilotEngine` working.

**Frontend**: dark-first "pro exchange" UI on Tailwind v4 + shadcn/ui (`src/components/ui/` is generated
shadcn code) + lucide + TradingView Lightweight Charts, server state with TanStack Query
(`src/lib/queries.ts`), and a typed client in `src/lib/api.ts` that maps every error to `ApiError` with the
server's RFC 7807 `code` and `correlationId`. Swaps are idempotent (an `Idempotency-Key` header). `@/` aliases `src/`.
The `/assistant` page (agent console: conversation + an inspector of engine, model, tokens, cost and tool calls)
and the side-panel `Copilot` share `src/lib/use-chat.ts`; each roadmap step extends that inspector.

## Gotchas that have bitten this repo

From `docs/engineering/10-bugs-and-lessons.md`. Read it before touching build, config or scheduling.

- **Shared migrations must be repeatable.** Schema added from platform-common goes in `db/platform` as an
  idempotent `R__*.sql` (`IF NOT EXISTS`). A versioned `V0_x` would sort before already-applied service
  migrations and Flyway refuses to start.
- **Spring ignores unknown YAML keys silently.** After editing `application.yml`, check the effective config
  (nesting under the wrong parent once disabled the gateway's Redis/timeouts with no error). Gateway properties
  live under `spring.cloud.gateway.server.webflux.*` (Spring Cloud 2025.0); `GatewayPropertiesTest` binds them.
- **Don't use exceptions for expected outcomes inside `@Transactional`.** A thrown exception that crosses the
  proxy marks the outer transaction rollback-only even if caught. Treat insufficient funds as data.
- **Don't define a `TaskScheduler` bean**: it replaces Boot's default scheduler. Critical background work
  gets its own executor (the default `@Scheduled` pool has one thread).
- **Map `timestamptz` explicitly** (`getTimestamp(...).toInstant()`). The driver doesn't support `Instant.class`.
- **Don't name env vars after library settings.** `PROMETHEUS_CONFIG` leaked into every JVM from the
  PowerShell script; Compose uses `PROMETHEUS_SERVER_CONFIG`.
- Root `pom.xml` sets `maven-jar-plugin forceCreation=true` (otherwise a platform-common change never
  reaches services whose own classes didn't change) and `-parameters` (needed for unnamed `@RequestParam`).
  Keep both. `opentelemetry-jdbc` (alpha) must match the OpenTelemetry API Boot manages; bump it with Boot.

## Conventions

- Java code uses compact formatting with **1-space indentation**. Match the surrounding file.
- Branches `feat/` `fix/` `docs/` `chore/`; commits like `fix(wallet): release hold when swap times out`;
  squash-merge; CI must be green. A `v*.*.*` tag publishes versioned GHCR images and a GitHub Release.
- Integration tests use real infrastructure via Testcontainers (`@DynamicPropertySource`, JDBC URL with
  `&currentSchema=...`). Mock only the outbound HTTP client (`@MockitoBean` on `MarketClient`/`AssetClient`)
  and use WireMock for CoinGecko. Use Awaitility instead of sleeps, and seeds instead of randomness.
- UI changes must keep the Playwright + axe accessibility tests passing.
- Diagrams in docs are Mermaid; validate with `npm run docs:diagrams`.
