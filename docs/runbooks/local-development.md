# Local development

## Services on the host, infrastructure in Docker (fastest feedback)
1. Requires Docker, JDK 21, Maven 3.9+, Node 20+.
2. `scripts/run-local.sh` (or `.\scripts\run-local.ps1`) starts Postgres, Redis, RabbitMQ, Prometheus and
   Grafana in Docker, builds, and starts all seven services from copies in `.run/jars/` (so you can
   rebuild while they run); logs go to `.run/logs/`. It waits until every service reports healthy.
   Use `SKIP_BUILD=1` / `-SkipBuild` to restart without rebuilding.
3. `cd frontend && npm install && npm run dev` and open http://localhost:5173 (Vite proxies `/api` to :8080).
4. `scripts/stop-local.sh` (or `.\scripts\stop-local.ps1`) stops the services; add `--infra` / `-Infra` to stop the containers too.

| Infra | Host port | Why not the default |
|---|---|---|
| PostgreSQL | 5433 | avoids a locally installed server on 5432 |
| Redis | 6380 | avoids a local Redis on 6379 |
| RabbitMQ | 5673 (UI 15673) | avoids WSL/local brokers on 5672 |

State lives in Postgres and survives restarts. `MARKET_PROVIDER=simulated` (plus `MARKET_SEED=42`)
switches asset-service to the offline simulator; the default is live CoinGecko
(`COINGECKO_API_KEY` is optional, for the demo-key tier).

## Inspecting things
- Postgres: `docker compose exec postgres psql -U opencrypto -d opencrypto` (`\dn` lists the per-service schemas)
- RabbitMQ queues and DLQs: http://localhost:15673 · Prometheus: http://localhost:9090 · Grafana: http://localhost:3000
- Logs: Grafana → "OpenCrypto logs" to find problems, "Request trace" for one correlation id (or Explore → Loki). Raw JSON is in `.run/logs/json/`, readable text in `.run/logs/*.log`.
  No logs in Grafana? Check the Alloy pipeline at http://localhost:12345 and `docker compose logs alloy`.
- Follow one request: copy `X-Correlation-Id` from a response (it's the W3C trace id) and open
  `http://localhost:3000/d/opencrypto-request?var-correlation_id=<id>` for its trace and logs, or `grep` it in `.run/logs/*.log`.
  No traces? Check Tempo is up (`docker compose ps tempo`, http://localhost:3200/ready).

## Docker Compose
1. Copy `.env.example` to `.env`.
2. Run `docker compose up --build -d`.
3. Inspect `docker compose ps` and `docker compose logs -f <service>`.
4. Open http://localhost:5173; the Status page shows every service's health. Logs are JSON (`json-logs` profile).

Reset local state with `docker compose down -v`; this deletes local Postgres, Redis and Grafana data.

## Checks before a PR
- `mvn clean verify` (unit + Testcontainers integration tests; needs Docker)
- `cd frontend && npm run build && npm run a11y`
- `docker compose config -q`
