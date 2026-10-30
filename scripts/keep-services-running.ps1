# Keep services running in foreground for daemon tasks
param()
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$services = 'asset-service','wallet-service','transaction-service','swap-service','ai-copilot-service','identity-service','api-gateway'
New-Item -ItemType Directory -Force .run\logs\json | Out-Null
New-Item -ItemType Directory -Force .run\jars | Out-Null

$env:PROMETHEUS_SERVER_CONFIG = 'prometheus-host.yml'
Remove-Item Env:PROMETHEUS_CONFIG -ErrorAction SilentlyContinue

$procs = @()
foreach ($svc in $services) {
  Copy-Item "services\$svc\target\$svc-0.1.0-SNAPSHOT.jar" ".run\jars\$svc.jar" -Force
  $env:LOGGING_FILE_NAME = ".run\logs\json\$svc.json"
  $env:LOGGING_STRUCTURED_FORMAT_FILE = 'ecs'
  $p = Start-Process java -ArgumentList '-Xmx384m','-jar',".run\jars\$svc.jar" `
    -RedirectStandardOutput ".run\logs\$svc.log" -RedirectStandardError ".run\logs\$svc.err.log" -WindowStyle Hidden -PassThru
  Set-Content -Path ".run\$svc.pid" -Value $p.Id
  Write-Host "Started $svc (pid $($p.Id))"
  $procs += $p
}

Write-Host "All services started. Waiting for health checks..."
for ($i = 0; $i -lt 150; $i++) {
  try {
    foreach ($svc in 'asset','wallet','swap','transaction','ai','identity') {
      Invoke-RestMethod "http://localhost:8080/api/v1/system/$svc/health" -TimeoutSec 2 | Out-Null
    }
    Write-Host 'Ready! All services healthy via gateway http://localhost:8080'
    break
  } catch {
    Start-Sleep 1
  }
}

Write-Host "Keeping daemon running indefinitely..."
$procs | Wait-Process

