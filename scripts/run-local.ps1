# Run the Java services on this machine against infrastructure in Docker (Windows PowerShell).
# Needs: Docker, JDK 21, Maven 3.9+.
#   .\scripts\run-local.ps1                 build + start (real CoinGecko prices)
#   $env:MARKET_PROVIDER='simulated'; .\scripts\run-local.ps1
#   .\scripts\run-local.ps1 -SkipBuild
param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$services = 'asset-service','wallet-service','transaction-service','swap-service','ai-copilot-service','identity-service','api-gateway'
New-Item -ItemType Directory -Force .run\logs\json | Out-Null

Write-Host 'Starting infrastructure (Postgres :5433, Redis :6380, RabbitMQ :5673/:15673, Prometheus :9090, Grafana :3000, Loki :3100, Alloy :12345, Tempo :3200/:4318)...'
# $env: is inherited by every process this session starts, so the name must not mean anything to Java:
# the Prometheus Java client in each service reads PROMETHEUS_CONFIG as its own config file and crashes.
$env:PROMETHEUS_SERVER_CONFIG = 'prometheus-host.yml'
Remove-Item Env:PROMETHEUS_CONFIG -ErrorAction SilentlyContinue   # left over from an older version of this script
docker compose up -d --wait postgres redis rabbitmq
if ($LASTEXITCODE -ne 0) { throw 'Infrastructure failed to start' }
docker compose up -d prometheus loki alloy tempo grafana

if (-not $SkipBuild) {
  Write-Host 'Building services...'
  mvn -B -q -DskipTests package
  if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' }
}

foreach ($svc in $services) {
  $pidFile = ".run\$svc.pid"
  if ((Test-Path $pidFile) -and (Get-Process -Id (Get-Content $pidFile) -ErrorAction SilentlyContinue)) {
    Write-Host "$svc already running (pid $(Get-Content $pidFile))"; continue
  }
  # run a copy: Windows locks a running jar, which would break the next build
  New-Item -ItemType Directory -Force .run\jars | Out-Null
  Copy-Item "services\$svc\target\$svc-0.1.0-SNAPSHOT.jar" ".run\jars\$svc.jar" -Force
  # console: readable text (.run\logs\<svc>.log); file: ECS JSON that Alloy ships to Loki/Grafana
  $env:LOGGING_FILE_NAME = ".run\logs\json\$svc.json"
  $env:LOGGING_STRUCTURED_FORMAT_FILE = 'ecs'
  $p = Start-Process java -ArgumentList '-Xmx384m','-jar',".run\jars\$svc.jar" `
    -RedirectStandardOutput ".run\logs\$svc.log" -RedirectStandardError ".run\logs\$svc.err.log" -WindowStyle Hidden -PassThru
  Set-Content -Path $pidFile -Value $p.Id
  Write-Host "Started $svc (pid $($p.Id), log .run\logs\$svc.log)"
}

Write-Host -NoNewline 'Waiting until every service is healthy (via the gateway) '
for ($i = 0; $i -lt 150; $i++) {
  try {
    foreach ($svc in 'asset','wallet','swap','transaction','ai','identity') { Invoke-RestMethod "http://localhost:8080/api/v1/system/$svc/health" -TimeoutSec 2 | Out-Null }
    Write-Host ' ready: UI http://localhost:5173 (cd frontend; npm run dev), API http://localhost:8080'; exit 0
  } catch { Write-Host -NoNewline '.'; Start-Sleep 1 }
}
Write-Host ' timed out; check .run\logs\*.log'; exit 1
