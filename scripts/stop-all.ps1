# Stops only the processes scripts\start-all.ps1 started (pid files in .run), in reverse start order.
# Docker containers are left running. Other processes are never touched.
$Root = Split-Path -Parent $PSScriptRoot
$RunDir = Join-Path $Root '.run'
foreach ($name in 'api-gateway', 'simulator-service', 'cooling-controller-service', 'scheduler-service', 'workload-service', 'telemetry-service', 'topology-service', 'eureka-server') {
  $f = Join-Path $RunDir "$name.pid"
  if (-not (Test-Path $f)) { continue }
  $p = Get-Process -Id ([int](Get-Content $f)) -ErrorAction SilentlyContinue
  if ($p) { Write-Host "[stop-all] stopping $name (pid $($p.Id))"; & taskkill /PID $p.Id /T /F | Out-Null }
  else { Write-Host "[stop-all] $name is not running (stale pid file removed)" }
  Remove-Item $f -Force
}
