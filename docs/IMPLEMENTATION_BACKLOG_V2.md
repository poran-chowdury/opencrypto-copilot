# Implementation Backlog — OpenCrypto Copilot v2

## P0 — Make the scaffold build and run
- [ ] Install/configure JDK 21 and verify Maven uses it.
- [ ] Run `mvn clean verify` from the root; fix compilation/test failures before feature work.
- [ ] Verify Docker Compose health checks and service routes.
- [ ] Add request correlation ID and structured error response.

## P1 — Financial correctness
- [ ] Implement persisted simulated wallet accounts and double-entry ledger.
- [ ] Enforce balanced journal entries in one DB transaction.
- [ ] Add idempotency key storage and unique constraints.
- [ ] Implement simulated swap state machine and transaction outbox.
- [ ] Test duplicate requests, timeout/retry, partial failure, and recovery.

## P2 — Market data
- [ ] Persist asset metadata and timestamped price snapshots.
- [ ] Add bounded external API client, timeout, retry/backoff, and rate limiting.
- [ ] Add Redis cache and stale-data response metadata.
- [ ] Build portfolio timeline from snapshots and ledger events.

## P3 — Grounded AI knowledge
- [ ] Add document ingestion with content hash and version metadata.
- [ ] Enable pgvector in the target Postgres image and choose embedding dimensions.
- [ ] Implement hybrid retrieval, reranking, access filtering, and citations.
- [ ] Add refusal/unknown-answer behavior for missing or stale evidence.
- [ ] Run the 50-case evaluation fixture and record baseline results.

## P4 — Evaluation and analytics
- [ ] Implement evaluation-run persistence and model adapter interface.
- [ ] Measure schema pass rate, grounding, citation validity, latency, and cost.
- [ ] Add explainable scenario calculations and time-based test splits for any ML experiment.
- [ ] Add drift reporting; never claim accuracy before measuring it.

## P5 — UI quality and deployment
- [ ] Build the dashboard and wallet/asset/swap pages.
- [ ] Run Playwright + axe-core in CI and publish scan artifacts.
- [ ] Add Prometheus/Grafana panels for request latency, failures, queue lag, stale prices, and AI cost.
- [ ] Add screenshots, demo data, runbook, and reproducible deployment steps.
