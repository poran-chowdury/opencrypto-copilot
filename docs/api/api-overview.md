# API reference

Base URL: `http://localhost:8080` (gateway). Conventions:

- **Identity:** `X-Demo-User: <id>` selects the demo account (letters, digits, `.`, `_`, `-`; max 40).
  `X-Demo-Role: user|staff` stands in for a JWT claim (staff can read INTERNAL knowledge).
- **Errors:** RFC 7807 `application/problem+json` with `code` (stable, machine-readable) and
  `correlationId`. Example: `{"status":409,"code":"QUOTE_ALREADY_USED","detail":"...","correlationId":"..."}`.
- **Correlation:** every response carries `X-Correlation-Id`; send your own to tie client logs in.
- **Idempotency:** `Idempotency-Key` (8-64 chars) is **required** on `POST /wallet/deposits` and
  `POST /swaps`. A replay returns the original result with `Idempotent-Replayed: true`; the same key
  with a different body returns `422 IDEMPOTENCY_KEY_REUSED`.
- **Pagination:** `?page=0&size=20` → `{items, page, size, totalItems, totalPages, sort}`; `size` ≤ 100.
- **Rate limits:** token bucket per user/IP. `X-RateLimit-Remaining|Burst-Capacity|Replenish-Rate`
  on every response; `429 RATE_LIMITED` with `Retry-After` when exceeded. Policies: read (30/s,
  burst 60), write (5/s, burst 10), ai (3/s, burst 20).
- **Money** is decimal; send amounts as strings (`"0.01"`) or JSON numbers, never floats you computed.

## Market (asset-service)

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/assets?page&size&sort=marketCap,desc&q&kind&sparkline=true` | Catalogue with latest prices. `sort` ∈ marketCap, price, change24h, volume, name, symbol, rank; `kind` ∈ CRYPTO, STABLECOIN, FIAT |
| GET | `/api/v1/assets/{symbol}` | Detail incl. description, networks (fees, confirmations), freshness |
| GET | `/api/v1/assets/{symbol}/history?range=1h\|24h\|7d\|30d&points=240` (or `from`/`to`) | Down-sampled price series |
| GET | `/api/v1/prices?symbols=BTC,ETH` | Latest prices from Redis (Postgres fallback) with `updatedAt`, `ageSeconds`, `stale` |
| GET | `/api/v1/market/status` | Provider, last sync, circuit breaker, freshness rule |
| GET | `/api/v1/market/snapshots?from&to&limit` | Time-aligned prices of all assets (portfolio valuation) |
| GET | `/api/v1/market/events?symbol&from&to&limit` | Market events (simulated provider) |
| GET | `/api/v1/market/dataset?seed&days&intervalMinutes&eventsPerDay&includeRegime&format=csv\|json` | Offline reproducible dataset |
| POST | `/api/v1/market/events`, `/simulation`, `/simulation/step`, `/simulation/reset` | Simulator controls (`MARKET_PROVIDER=simulated` only; otherwise `409 SIMULATION_DISABLED`) |

## Wallet (wallet-service)

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/portfolio` | Balances (available/held), values, allocation, cost basis, unrealised/realised P/L |
| GET | `/api/v1/portfolio/timeline?from&to&limit` | Value over time rebuilt from the ledger + ledger events |
| GET | `/api/v1/wallet/balances` | Raw balances per asset and bucket |
| GET | `/api/v1/wallet/ledger?page&size&type` | Journal entries with double-entry postings |
| GET | `/api/v1/wallet/audit?page&size` | Append-only audit trail |
| POST | `/api/v1/wallet/deposits` + `Idempotency-Key` | `{"symbol":"BTC","amount":"0.01"}` → 201 (or 200 on replay) |
| POST | `/api/v1/wallet/reset` | Close balances and re-open with 10,000 USD (409 while a swap holds funds) |
| GET / POST | `/api/v1/wallet/reconciliation`, `/reconciliation/run` | Latest / run ledger reconciliation |

## Swaps (swap-service)

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/swaps/quotes` | `{"fromSymbol","toSymbol","amount","slippageBps"}` → quote (30 s) with fee, rate, estimatedOutput, minimumReceived. 503 `PRICES_STALE` if market data is old |
| POST | `/api/v1/swaps` + `Idempotency-Key` | `{"quoteId"}` → **202 Accepted** with the order; poll for the outcome. 409 `QUOTE_EXPIRED` / `QUOTE_ALREADY_USED` |
| GET | `/api/v1/swaps?page&size&state` | Your orders |
| GET | `/api/v1/swaps/{id}` | Order with `stateHistory` |

States: `CREATED → PENDING → COMPLETED | FAILED`. Failure reasons: `INSUFFICIENT_FUNDS`,
`PRICE_MOVED`, `MARKET_UNAVAILABLE`, `TIMEOUT`, `RESERVATION_CANCELLED`, `RESERVATION_*`,
`NO_RESERVATION`.

## Activity (transaction-service)

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/transactions?type&status&from&to&sort=createdAt,desc&page&size` | Activity feed (SWAP, DEPOSIT, RESET) |
| GET | `/api/v1/transactions/{id}` | One record |

## System

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/system/{asset\|wallet\|swap\|transaction\|ai\|identity}/health` | Service health (read-only route to `/actuator/health`) |

## AI copilot (ai-copilot-service)

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/ai/chat` | `{"message","history","engine"}` → answer, tool calls, proposed action, caveats |
| GET | `/api/v1/ai/status`, `/api/v1/ai/tools` | Engines/retrievers/tools; tool JSON schemas |
| POST | `/api/v1/ai/tools/{name}` | Invoke one read-only tool |
| GET / POST | `/api/v1/ai/knowledge/documents`, `/search`, `/reload` | Knowledge corpus and retrieval |

## Not routed by the gateway

`/internal/**` (none remain after the move to messaging) and `/actuator/**` except the health route
above. Service-to-service money movement happens over RabbitMQ; see [messaging.md](../architecture/messaging.md).
