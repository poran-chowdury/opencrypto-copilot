#!/usr/bin/env bash
# k6 load test against the running stack (scripts/run-local.sh first). k6 runs in Docker: nothing to install.
#   scripts/load-test.sh                       smoke: 1 swap/s for 30 s
#   PROFILE=ramp scripts/load-test.sh          2 → 12 swaps/s, one minute per step
#   PROFILE=steady RATE=6 DURATION=3m scripts/load-test.sh
#   SCRIPT=browse.js PROFILE=ramp scripts/load-test.sh
# In a second terminal: python loadtest/watch.py   (queues, pools, outbox, CPU every 10 s)
# k6 metrics also go to Prometheus (k6_*, tagged testid=<run>), next to the services' own metrics.
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: don't rewrite /scripts into a Windows path
RUN_ID="${RUN_ID:-${PROFILE:-smoke}-$(date +%Y%m%d-%H%M%S)}"
HOST_DIR="$(pwd -W 2>/dev/null || pwd)/loadtest"
echo "Run $RUN_ID (users are named load-$RUN_ID-<n>)"
docker run --rm -i --add-host=host.docker.internal:host-gateway -v "$HOST_DIR:/scripts:ro" \
  -e BASE_URL=http://host.docker.internal:8080 -e PROFILE="${PROFILE:-smoke}" -e RATE="${RATE:-4}" \
  -e DURATION="${DURATION:-3m}" -e USERS="${USERS:-100}" -e POLL_MS="${POLL_MS:-750}" -e RUN_ID="$RUN_ID" \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://host.docker.internal:9090/api/v1/write \
  -e 'K6_PROMETHEUS_RW_TREND_STATS=p(50),p(95),p(99),max' \
  grafana/k6:1.0.0 run -o experimental-prometheus-rw --tag testid="$RUN_ID" "/scripts/${SCRIPT:-swaps.js}"
