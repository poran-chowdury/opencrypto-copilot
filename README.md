# OpenCrypto Copilot

[![CI/CD](https://github.com/poran-chowdury/opencrypto-copilot/actions/workflows/cd.yml/badge.svg)](https://github.com/poran-chowdury/opencrypto-copilot/actions/workflows/cd.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 3.4](https://img.shields.io/badge/Spring%20Boot-3.4-6db33f)
![React 19](https://img.shields.io/badge/React-19-61dafb)

A crypto portfolio and trading platform built as a **production-style microservices system**:
real market prices from CoinGecko, a double-entry ledger, a RabbitMQ swap saga, gateway rate
limiting, full observability, and a React product UI. Balances and trades are **simulated**, so
no real money, custody or blockchain is involved.

It is also a learning project: every engineering decision is written up as *problem → options →
fix → how to verify* in **[docs/engineering](docs/engineering/README.md)**, and the AI phase builds
on top of it ([docs/ai/projects.md](docs/ai/projects.md)).

## What it does

| Feature | How it's built |
|---|---|
| **Live markets**: 13 assets with prices, 24h change, market cap, volume, 7-day sparklines, charts | CoinGecko sync every 60 s (ShedLock), Redis cache with Postgres fallback, retry + circuit breaker, staleness flags, `date_bin` down-sampled history |
| **Wallet**: demo deposits, balances, average-cost P/L, ledger, audit log | Double-entry ledger with invariants enforced in Postgres, row locking, idempotency keys, holds, reconciliation job |
| **Trading**: quotes with fee, slippage and minimum received; live order tracking | Orchestrated saga over RabbitMQ (CREATED → PENDING → COMPLETED/FAILED), transactional outbox, idempotent consumers, retries + DLQs, timeout recovery |
| **Activity**: filterable, paginated history | Read model built from events (CQRS-style) |
| **Platform**: rate limits, health, tracing, metrics, logs, alerts | Gateway token bucket in Redis; W3C Trace Context + OpenTelemetry traces across HTTP, **RabbitMQ (through the outbox)** and **every SQL statement** in Tempo; JSON logs in Loki via Alloy; Prometheus alerts with exemplars; service map; all linked in provisioned Grafana dashboards |
| **AI copilot** (rule-based for now) | Typed read-only tools over the APIs, pluggable engines, knowledge base. The LLM work is [phase 2](docs/ai/projects.md) |

## Run it

**Option A, recommended for development** (needs Docker, JDK 21, Maven 3.9+, Node 20+):

```bash
scripts/run-local.sh                   # Windows: .\scripts\run-local.ps1
cd frontend && npm install && npm run dev
```

Infrastructure runs in Docker; the seven Java services run on your machine (logs in `.run/logs/`).
Stop with `scripts/stop-local.sh` (add `--infra` to stop the containers too).

**Option B, everything in Docker:**

```bash
cp .env.example .env
docker compose up --build -d
```

| URL | What |
|---|---|
| http://localhost:5173 | Web app |
| http://localhost:8080 | API gateway ([API reference](docs/api/api-overview.md)) |
| http://localhost:3000 | Grafana: dashboards "OpenCrypto overview" (metrics) , "OpenCrypto logs" (errors, failed requests, search) and "Request trace" (one request across all services) |
| http://localhost:9090/alerts | Prometheus alert rules |
| http://localhost:12345 | Grafana Alloy (log shipping pipeline) |
| http://localhost:3200 | Tempo (traces; OTLP in on :4318). Open traces in Grafana → Request trace |
| http://localhost:15673 | RabbitMQ (opencrypto / localdev_only_change_me) |

**Option C, the images CI/CD published** (no JDK or Node needed, no local build):

```bash
cp .env.example .env
docker compose -f docker-compose.yml -f docker-compose.release.yml up -d
```

Infra host ports are **5433** (Postgres), **6380** (Redis), **5673** (RabbitMQ) so they don't clash
with locally installed servers. Offline? Start with `MARKET_PROVIDER=simulated` for a synthetic
market (with shocks, outages and seeds in the Market Lab).

## Test it

```bash
mvn test        # unit tests, no Docker
mvn verify      # + Testcontainers integration tests (Postgres, Redis, RabbitMQ, WireMock)
cd frontend && npm run build && npm run a11y   # type-check/build + Playwright/axe accessibility
scripts/load-test.sh                           # k6 load test (PROFILE=ramp); see docs/engineering/11-load-testing.md
```

## CI/CD

Every pull request runs [CI](.github/workflows/ci.yml): unit and Testcontainers tests, the frontend
build, and the axe accessibility lab. Every push to `main` runs CI again and then
[CD](.github/workflows/cd.yml) publishes one Docker image per service to GitHub Container Registry;
a `v*.*.*` tag also publishes versioned images and a GitHub Release. Dependabot opens weekly update
PRs. How and why it's set up this way: [docs/engineering/12-github-ci-cd.md](docs/engineering/12-github-ci-cd.md).

## Repository layout

```text
libs/platform-common/      correlation ids, RFC 7807 errors, outbox/inbox, RabbitMQ conventions
services/api-gateway/      routing, Redis rate limiting, correlation ids
services/asset-service/    CoinGecko sync, prices, history, simulator, datasets
services/wallet-service/   double-entry ledger, holds, P/L, audit, reconciliation, saga steps
services/swap-service/     quotes, swap saga orchestration, recovery
services/transaction-service/  activity read model
services/ai-copilot-service/   tools, engines, knowledge retrieval (AI phase)
services/identity-service/     identity boundary (demo accounts for now)
frontend/                  React 19 + TypeScript, Tailwind v4 + shadcn/ui, TradingView Lightweight Charts, TanStack Query
infra/                     Postgres init (schema per service), Prometheus, Grafana
docs/engineering/          learning notes: the problems and the fixes
docs/ai/                   AI phase: project starter kits
knowledge/ evaluation/ analytics/   AI phase assets (corpus, eval sets, ML baselines)
```

## What's deliberately not done

- **Authentication**: users are identified by a demo header (`X-Demo-User`); JWT validation at the
  gateway is the natural next step.
- **Real money**: no custody, withdrawals or blockchain. Balances are simulated.
- **Tail sampling**: traces sample 100% locally; there's no OpenTelemetry Collector for tail sampling yet.

## Learn from it

Start with [docs/engineering/README.md](docs/engineering/README.md). Chapter 10 lists 24 real bugs
from building this project, and [resume.md](docs/engineering/resume.md) shows how to describe it.
