# k6 load test against the running stack (.\scripts\run-local.ps1 first). k6 runs in Docker: nothing to install.
#   .\scripts\load-test.ps1                                  smoke: 1 swap/s for 30 s
#   .\scripts\load-test.ps1 -Profile ramp                    2 -> 12 swaps/s, one minute per step
#   .\scripts\load-test.ps1 -Profile steady -Rate 6 -Duration 3m
#   .\scripts\load-test.ps1 -Script browse.js -Profile ramp
# In a second terminal: python loadtest\watch.py   (queues, pools, outbox, CPU every 10 s)
# k6 metrics also go to Prometheus (k6_*, tagged testid=<run>), next to the services' own metrics.
param([string]$Profile = 'smoke', [int]$Rate = 4, [string]$Duration = '3m', [int]$Users = 100, [int]$PollMs = 750, [string]$Script = 'swaps.js')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$runId = "$Profile-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
Write-Host "Run $runId (users are named load-$runId-<n>)"
docker run --rm -i --add-host=host.docker.internal:host-gateway -v "${root}\loadtest:/scripts:ro" `
  -e BASE_URL=http://host.docker.internal:8080 -e PROFILE=$Profile -e RATE=$Rate -e DURATION=$Duration `
  -e USERS=$Users -e POLL_MS=$PollMs -e RUN_ID=$runId `
  -e K6_PROMETHEUS_RW_SERVER_URL=http://host.docker.internal:9090/api/v1/write `
  -e 'K6_PROMETHEUS_RW_TREND_STATS=p(50),p(95),p(99),max' `
  grafana/k6:1.0.0 run -o experimental-prometheus-rw --tag testid=$runId "/scripts/$Script"
exit $LASTEXITCODE
