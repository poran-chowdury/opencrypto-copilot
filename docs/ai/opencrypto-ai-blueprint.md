# OpenCrypto Copilot — AI Portfolio Upgrade Blueprint

## Goal

Turn OpenCrypto Copilot from a basic microservice scaffold into a portfolio-grade crypto analytics application that demonstrates backend engineering, applied AI, evaluation, security, and observability. The product uses **simulated balances only**. It does not custody assets, sign blockchain transactions, or execute real trades.

## Adapt the five AI project patterns to crypto

### 1. Crypto Knowledge Assistant (RAG)

**User problem:** People need answers about supported assets, network fees, simulated swap rules, portfolio metrics, and crypto concepts without receiving invented facts.

**Build:**
- Ingest curated Markdown/PDF documentation and asset/network reference material.
- Store document chunks with source, page/section, version, publication date, and access scope.
- Use PostgreSQL full-text search plus `pgvector` semantic search; rerank candidates before generation.
- Answer with source citations. If evidence is missing or stale, say that the answer is unknown or needs verification.
- Filter by access permissions and document version before retrieval.
- Treat retrieved documents as untrusted content, not instructions.

**Proposed endpoints:**
- `POST /api/ai/knowledge/documents` — admin-only document ingestion.
- `POST /api/ai/knowledge/search` — retrieve passages and sources.
- `POST /api/ai/knowledge/answer` — grounded answer with citations.

**Acceptance checks:** 50+ question set; citation correctness; retrieval hit rate; unsupported-answer rate; stale-source detection; prompt-injection tests.

### 2. AI-Assisted Accessibility Auditor for the web app

This is a supporting developer tool, not a core crypto feature. Once a React/Next.js UI exists, run Playwright and axe-core against pages such as dashboard, asset detail, wallet history, and swap quote.

- Use axe-core for deterministic rule-based checks.
- Optionally send screenshots to a vision-capable model for contextual observations.
- Label findings as `RULE_DETECTED` or `AI_SUGGESTED`; AI suggestions require human review.
- Store scan history and fail CI for agreed critical violations.

**Acceptance checks:** seed known accessibility issues in a demo page; measure detected and missed issues; run scans in GitHub Actions.

### 3. Market Event Companion (adapted from the real-time lecture companion)

Replace lecture audio/slides with a timestamped market timeline. It should help users understand what changed in their simulated portfolio without claiming that news caused a price move unless evidence supports that conclusion.

- Ingest price snapshots and curated/public news metadata asynchronously.
- Persist timestamps, asset/network identifiers, source URLs, and ingestion status.
- Correlate user-visible price changes with events by time window; present correlations as possible context, not proven causation.
- Let a user ask, “What changed in my portfolio around 14:00?” and retrieve price history, allocation changes, and available event records.
- Retry failed ingestion jobs; preserve raw source metadata and avoid silently dropping gaps.

**Proposed endpoints:**
- `GET /api/market/events?assetId=...&from=...&to=...`
- `GET /api/portfolio/timeline?from=...&to=...`
- `POST /api/ai/explain-timeline`

### 4. Portfolio Risk & Scenario Forecaster (adapted from bike demand forecasting)

Do **not** promise exact future crypto prices. Forecast portfolio exposure or volatility scenarios using historical observations and clearly stated assumptions.

- Start with a transparent baseline (historical rolling volatility / previous-period baseline).
- Optionally add a Python FastAPI + scikit-learn service for a time-series experiment; keep Java as the main business backend.
- Use time-ordered train/validation/test splits, never random splits across time.
- Track MAE for numeric forecasts and compare against the baseline.
- Save model version, feature window, forecast, assumptions, and eventual observed outcome.
- Detect performance drift and flag a model for review rather than automatically presenting unreliable predictions.

**Example outputs:** allocation concentration, historical volatility bands, simulated ±5%/±10% asset shocks, and scenario impact on a demo portfolio. These are educational scenarios, not investment advice.

**Proposed endpoints:**
- `POST /api/analytics/scenarios`
- `GET /api/analytics/forecasts/{id}`
- `GET /api/analytics/model-performance`

### 5. AI Model Evaluation Arena

Make AI quality measurable instead of selecting a model based only on opinion.

- Run the same versioned prompt and test cases against two or more configured model providers.
- Validate structured outputs using JSON Schema or typed DTO validation.
- Record correctness, evidence/citation validity, schema pass rate, latency, token usage, cost estimate, refusal quality, and prompt-injection robustness.
- Include ambiguous questions, missing data, stale sources, and malicious instructions.
- Manually review a sample of scored outputs to detect bad automated scoring.
- Never include secrets or private user data in evaluation fixtures.

**Proposed endpoints:**
- `POST /api/ai/evaluations/runs`
- `GET /api/ai/evaluations/runs/{runId}`
- `GET /api/ai/evaluations/compare?runA=...&runB=...`

## Suggested service boundaries

Keep the current services and add only what earns its complexity:

- `api-gateway`: routing, request IDs, rate limits, authentication boundary.
- `identity-service`: user identity and roles.
- `asset-service`: asset metadata, market-data ingestion, cached price snapshots.
- `wallet-service`: simulated wallet and double-entry ledger.
- `transaction-service`: transaction history and audit trail.
- `swap-service`: quote and simulated swap state machine; idempotency and recovery.
- `ai-copilot-service`: RAG orchestration, grounded explanations, tool allowlist, evaluation API.
- Optional `analytics-ml-service` (Python/FastAPI): isolated forecasting experiments only if a model is actually needed.

Use RabbitMQ for ingestion and workflow events, PostgreSQL for durable records, Redis for short-lived cache, and Prometheus/Grafana for observability. Use the transactional outbox pattern for database changes that must publish events reliably.

## Core safety and correctness rules

1. AI must not write ledger entries, change balances, or execute swaps directly.
2. Any simulated swap must pass through the swap service and its normal validation/state machine.
3. Never trust model-generated user IDs, asset IDs, prices, fees, or permissions. Resolve them from authenticated context and backend data.
4. Every AI answer about portfolio data must be grounded in a service response or retrieved source, with timestamps where relevant.
5. If sources conflict or are stale, disclose uncertainty instead of silently selecting an answer.
6. Keep secrets in environment variables/secret storage; never commit API keys.
7. Do not market scenario output as financial advice or guaranteed prediction.

## Delivery milestones

### Milestone 1 — Reliable foundation
- Fix local Java 21 toolchain; verify `java -version`, `javac -version`, and `mvn -version`.
- Add persistence and migrations, service-level integration tests, request correlation IDs, health checks.
- Implement wallet ledger, idempotent simulated swap, and recovery tests before adding more AI complexity.

### Milestone 2 — Market data and portfolio timeline
- Asset metadata and price snapshots, scheduled ingestion, Redis cache, source timestamps, stale-data flags.
- Timeline endpoint for portfolio value and transaction events.

### Milestone 3 — Grounded AI knowledge assistant
- Document ingestion, hybrid retrieval, citations, permission filtering, unknown-answer behavior.
- Add the evaluation fixture and retrieval/answer metrics.

### Milestone 4 — Evaluation and risk scenarios
- Compare models using the same evaluation cases.
- Add explainable scenario calculations first; only add a separate ML service if the baseline and dataset justify it.

### Milestone 5 — Accessibility and polished demo
- Add a frontend, automated accessibility scan, dashboards, runbook, screenshots, and a reproducible demo.
- Publish real measured test results only; do not claim performance improvements until measured.

## What is intentionally not included yet

This blueprint and fixture are implementation assets, not proof that RAG, model evaluation, forecasting, accessibility scanning, or live market ingestion is already implemented. Build each milestone and add tests before describing it as complete.
