# Who is using the CPU? Samples every N seconds: each Java service (by the pid run-local wrote),
# each Docker container, and the machine total. Percent of the WHOLE machine (all cores), so the rows add up.
#   .\loadtest\cpu.ps1                    every 10 s until Ctrl+C
#   .\loadtest\cpu.ps1 -Samples 30 -Csv cpu.csv
param([int]$Every = 10, [int]$Samples = 0, [string]$Csv = '')
$root = Split-Path -Parent $PSScriptRoot
$cores = [Environment]::ProcessorCount
$pids = @{}
Get-ChildItem "$root\.run\*.pid" | ForEach-Object { $pids[[int](Get-Content $_.FullName)] = $_.BaseName -replace '-service$', '' }
function JavaCpu {
  $now = @{}
  foreach ($id in $pids.Keys) { $p = Get-Process -Id $id -ErrorAction SilentlyContinue; if ($p) { $now[$pids[$id]] = $p.TotalProcessorTime.TotalSeconds } }
  return $now
}
$prev = JavaCpu; $t0 = Get-Date
if ($Csv) { 'time,component,cpu_pct' | Out-File $Csv -Encoding utf8 }
$n = 0
while ($true) {
  Start-Sleep -Seconds $Every
  $cur = JavaCpu; $t1 = Get-Date; $dt = ($t1 - $t0).TotalSeconds
  $rows = [ordered]@{}
  foreach ($k in $cur.Keys) { if ($prev.ContainsKey($k)) { $rows["java:$k"] = 100 * ($cur[$k] - $prev[$k]) / $dt / $cores } }
  # docker stats reports % of ONE core (200% = two cores busy); divide by the cores to get % of the machine.
  docker stats --no-stream --format '{{.Name}};{{.CPUPerc}}' | ForEach-Object {
    $name, $pct = $_ -split ';'; $rows["docker:" + ($name -replace '^opencrypto-copilot-|-1$', '')] = [double]($pct -replace '%', '') / $cores
  }
  $machine = (Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average
  $stamp = $t1.ToString('HH:mm:ss')
  $top = $rows.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 8
  Write-Host (("$stamp machine {0,3}% | " -f $machine) + (($top | ForEach-Object { '{0} {1:N0}%' -f $_.Key, $_.Value }) -join ', '))
  if ($Csv) { foreach ($r in $rows.GetEnumerator()) { "$stamp,$($r.Key),$([math]::Round($r.Value, 1))" | Out-File $Csv -Append -Encoding utf8 }; "$stamp,machine,$machine" | Out-File $Csv -Append -Encoding utf8 }
  $prev = $cur; $t0 = $t1; $n++
  if ($Samples -and $n -ge $Samples) { break }
}
