# Putting this project on your resume

Only claim what you can explain and demo. Every bullet below maps to code, a test, or a command in
these docs. Adjust the wording to what *you* built and understand.

## Project line

**OpenCrypto: crypto portfolio & simulated trading platform** · Java 21, Spring Boot 3.4, PostgreSQL,
Redis, RabbitMQ, React 19/TypeScript, Docker, Prometheus/Grafana, Testcontainers

## Bullets (pick 4-6)

- Built a **double-entry ledger** for simulated wallets with invariants enforced in PostgreSQL
  (deferred balance trigger, non-negative CHECK, append-only triggers), pessimistic row locking in
  a fixed order, and **idempotency keys**; verified that 20 concurrent withdrawals against one
  balance never overspend (Testcontainers).
- Designed a **saga-based swap workflow** over RabbitMQ (CREATED → PENDING → COMPLETED/FAILED) with
  a **transactional outbox**, idempotent consumers, retries with exponential backoff and
  **dead-letter queues**; timeouts resolve by asking the wallet, so a "failed" swap can never have
  moved money (proven by crash-testing the wallet mid-swap).
- Integrated the **CoinGecko API** through a ShedLock-guarded scheduled sync with Resilience4j
  retries and **circuit breaker**, a Redis write-through cache with Postgres fallback, staleness
  metadata, and down-sampled price history; paginated/filterable/sortable REST APIs with a
  SQL-injection-safe sort whitelist.
- Implemented **distributed rate limiting** at the Spring Cloud Gateway (Redis token bucket via
  Lua, per-user keys, cost-based read/write/AI policies, RFC 7807 429 responses with Retry-After).
- Added **production-style observability**: **distributed tracing with W3C Trace Context and
  OpenTelemetry** (Grafana Tempo) that follows a request across HTTP *and* RabbitMQ, including through
  the transactional outbox, down to each SQL statement and connection checkout (sanitized, with a sampler
  rule that keeps background queries out), with route-based span names and a filterable trace list; structured JSON logs in Grafana Loki linked to traces; RED and business metrics with exemplars in Prometheus, alert rules (ledger
  reconciliation, outbox backlog, DLQ, circuit breakers), and a Grafana dashboard provisioned as code.
- Set up **CI/CD with GitHub Actions**: every PR runs unit, Testcontainers and accessibility tests;
  merges to `main` reuse the same CI workflow as a gate, then a matrix job publishes 8 Docker images
  to GitHub Container Registry with immutable `sha-` and SemVer tags and layer caching; version tags
  create a GitHub Release. Branch rulesets require green checks, and Dependabot keeps dependencies current.
- Wrote **49 automated tests**, including Testcontainers integration tests against real
  Postgres/Redis/RabbitMQ and WireMock-scripted third-party failures, plus Playwright + axe
  accessibility checks in CI.
- **Load-tested** the swap saga with **k6** (open-model ramp) and diagnosed a metastable failure with distributed
  traces and Prometheus (connection-pool exhaustion → scheduler-thread starvation → outbox stall); verified
  ledger correctness under overload (every reservation released or captured, reconciliation clean); two fixes
  (a dedicated outbox thread, 4 wallet consumers) raised the healthy rate from ~5 to 6 swaps/s and doubled completions.
- Built a responsive, dark-first **React/TypeScript** trading UI with **Tailwind CSS + shadcn/ui** and
  **TradingView Lightweight Charts** (⌘K command palette, live ticker, area/candle charts, live order
  tracking, code-split routes), with **zero axe accessibility findings** enforced by Playwright in CI.

## Interview stories (STAR)

**"Tell me about a hard distributed-systems problem."**
*S:* A swap spans swap-service and wallet-service; no shared transaction. *T:* never lose or
double-move money, even with crashes and duplicate messages. *A:* orchestrated saga, outbox in the
same DB transaction as the state change, inbox dedupe, idempotent steps keyed by swap id, holds in
separate ledger accounts, and timeouts that *ask* the wallet instead of guessing. *R:* killed the
wallet mid-swap: commands waited in RabbitMQ, the swap ended FAILED(TIMEOUT) with funds released
and reconciliation clean; five saga scenarios automated in `SwapSagaIT`.

**"How do you prevent double spending?"**
Row locks in id order + check under lock + DB CHECK constraint as the safety net; concurrency test
with a `CountDownLatch` releasing 20 threads at once: exactly 10 of 20 succeed.

**"Tell me about a bug you debugged."** Pick one from [chapter 10](10-bugs-and-lessons.md): the
port-collision "wrong password" (#5) or the timeout design flaw (#10) are good ones.

**"How would you scale this?"** Stateless services behind the gateway; ShedLock for jobs;
`SKIP LOCKED` outbox publishing works with N instances; Redis-backed rate limits are shared;
schemas can move to separate databases; add partitioning/retention for price history.

## Be ready to say what's *not* done

No real authentication (demo user header; JWT at the gateway is the next step), no real money or
blockchain, 100% trace sampling with no tail sampling yet, single Postgres instance.
Saying this clearly is a strength in interviews.
