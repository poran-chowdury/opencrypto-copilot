# System design

## Functional requirements
Live asset catalogue and prices with history; simulated wallet with deposits, balances, P/L,
ledger and audit trail; quotes and asynchronous swaps after explicit confirmation; activity
history; an ops status view; an AI copilot that explains backend data through read-only tools.

## Non-functional requirements
Ledger correctness under concurrency, idempotent writes, recoverable workflows across service
crashes, user isolation, rate limiting at the edge, graceful degradation when CoinGecko, Redis or a
service is down, observability (correlation ids, metrics, alerts), and clear demo-money labelling.

## Boundaries and data ownership
| Service | Owns (schema) | Sync deps | Async |
|---|---|---|---|
| api-gateway | — (Redis buckets) | all | — |
| asset-service | `asset`: catalogue, prices, history, events, sync runs | CoinGecko | — |
| wallet-service | `wallet`: accounts, journal, postings, reservations, positions, audit | asset-service | consumes `wallet.cmd.*`, emits `wallet.evt.*` |
| swap-service | `swap`: quotes, orders, state history | asset-service | emits `wallet.cmd.*`, `swap.evt.*`; consumes `wallet.evt.*` |
| transaction-service | `txn`: activity read model | — | consumes `swap.evt.*`, `wallet.evt.deposit-completed/wallet-reset` |
| ai-copilot-service | knowledge files | all (read-only) | — |

Each schema has its own Postgres login; cross-schema access is denied by the database.

## Key decisions
See the [ADRs](adr/). In short: schema-per-service on one Postgres for local simplicity; HTTP for
queries, RabbitMQ for workflows; an orchestrated saga with transactional outbox/inbox; the wallet is
the authority on money; Redis is a cache and rate-limit store, never a source of truth; Compose,
not Kubernetes. Deep dives: [docs/engineering](../engineering/README.md). Message contracts:
[messaging.md](messaging.md).

## Reliability patterns
Idempotency keys with request hashing, DB-enforced ledger invariants, pessimistic locks in id
order, transactional outbox with publisher confirms, idempotent consumers, retries with backoff and
DLQs, saga timeouts resolved by the wallet, a reconciliation job, circuit breakers and timeouts on
every HTTP client, and alerts on reconciliation mismatches, outbox backlog, DLQ depth and stale data.
