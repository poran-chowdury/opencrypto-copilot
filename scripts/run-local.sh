#!/usr/bin/env bash
# Run the Java services on this machine against infrastructure in Docker.
# Needs: Docker, JDK 21, Maven 3.9+.
#   scripts/run-local.sh                      build + start (real CoinGecko prices)
#   MARKET_PROVIDER=simulated scripts/run-local.sh
#   SKIP_BUILD=1 scripts/run-local.sh         restart without rebuilding
set -euo pipefail
cd "$(dirname "$0")/.."
SERVICES=(asset-service wallet-service transaction-service swap-service ai-copilot-service identity-service api-gateway)
mkdir -p .run/logs/json

echo "Starting infrastructure (Postgres :5433, Redis :6380, RabbitMQ :5673/:15673, Prometheus :9090, Grafana :3000, Loki :3100, Alloy :12345, Tempo :3200/:4318)..."
PROMETHEUS_SERVER_CONFIG=prometheus-host.yml docker compose up -d --wait postgres redis rabbitmq
PROMETHEUS_SERVER_CONFIG=prometheus-host.yml docker compose up -d prometheus loki alloy tempo grafana

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  echo "Building services..."
  mvn -B -q -DskipTests package
fi

for svc in "${SERVICES[@]}"; do
  if [[ -f ".run/$svc.pid" ]] && kill -0 "$(cat ".run/$svc.pid")" 2>/dev/null; then
    echo "$svc already running (pid $(cat ".run/$svc.pid"))"; continue
  fi
  # run a copy: Windows locks a running jar, which would break the next build
  mkdir -p .run/jars && cp "services/$svc/target/$svc-0.1.0-SNAPSHOT.jar" ".run/jars/$svc.jar"
  # console: readable text (.run/logs/<svc>.log); file: ECS JSON that Alloy ships to Loki/Grafana
  LOGGING_FILE_NAME=".run/logs/json/$svc.json" LOGGING_STRUCTURED_FORMAT_FILE=ecs     java -Xmx384m -jar ".run/jars/$svc.jar" > ".run/logs/$svc.log" 2>&1 &
  echo $! > ".run/$svc.pid"
  echo "Started $svc (pid $!, log .run/logs/$svc.log)"
done

echo -n "Waiting until every service is healthy (via the gateway) "
for _ in $(seq 1 150); do
  ok=1
  for svc in asset wallet swap transaction ai identity; do
    curl -fs "http://localhost:8080/api/v1/system/$svc/health" > /dev/null 2>&1 || { ok=0; break; }
  done
  if [[ $ok == 1 ]]; then echo " ready: UI http://localhost:5173 (cd frontend && npm run dev), API http://localhost:8080"; exit 0; fi
  echo -n "."; sleep 1
done
echo " timed out; check .run/logs/*.log"; exit 1
