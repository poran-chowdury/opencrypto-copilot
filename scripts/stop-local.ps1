# Stop services started by scripts\run-local.ps1. Use -Infra to also stop the Docker containers.
param([switch]$Infra)
$root = Split-Path -Parent $PSScriptRoot
Get-ChildItem "$root\.run\*.pid" -ErrorAction SilentlyContinue | ForEach-Object {
  $procId = Get-Content $_.FullName
  try { Stop-Process -Id $procId -Force -ErrorAction Stop; Write-Host "Stopped $($_.BaseName)" } catch { }
  Remove-Item $_.FullName
}
if ($Infra) { Set-Location $root; docker compose stop }
