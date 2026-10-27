#!/usr/bin/env bash
# Stop services started by scripts/run-local.sh. Add --infra to also stop the Docker containers.
cd "$(dirname "$0")/.."
for pidfile in .run/*.pid; do
  [[ -f "$pidfile" ]] || continue
  kill "$(cat "$pidfile")" 2>/dev/null && echo "Stopped $(basename "$pidfile" .pid)"
  rm -f "$pidfile"
done
if [[ "${1:-}" == "--infra" ]]; then docker compose stop; fi
