<#
.SYNOPSIS
  Starts ThermoGrid for local development in plan section 13 order (consumers before producers).
.DESCRIPTION
  1 MySQL and RabbitMQ (Docker), 2 eureka-server, 3 auth, 4 topology, 5 telemetry, 6 workload, 7 scheduler,
  8 cooling controller, 9 simulator, 10 api-gateway. Mirrors scripts/start-all.sh.
  -DryRun      print the plan and readiness checks, start nothing
  -SkipBuild   do not run "mvnw install" first
  -NoInfra     do not touch Docker (assume MySQL and RabbitMQ run)
  Secrets are read from the environment or the git-ignored .env. Nothing is hard-coded. Processes started here are
  recorded in .run\*.pid; stop them with scripts\stop-all.ps1. Other processes are never touched.
#>
param([switch]$DryRun, [switch]$SkipBuild, [switch]$NoInfra, [int]$ReadyTimeout = 180)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$RunDir = Join-Path $Root '.run'
$LogDir = Join-Path $RunDir 'logs'
$Eureka = if ($env:EUREKA_BASE) { $env:EUREKA_BASE } else { 'http://localhost:8761' }

# name, module, port, eureka application ('-' = not registered)
$Services = @(
  @('eureka-server', 'eureka-server', 8761, '-'),
  @('auth-service', 'auth-service', 8081, 'AUTH-SERVICE'),
  @('topology-service', 'topology-service', 8082, 'TOPOLOGY-SERVICE'),
  @('telemetry-service', 'telemetry-service', 8084, 'TELEMETRY-SERVICE'),
  @('workload-service', 'workload-service', 8085, 'WORKLOAD-SERVICE'),
  @('scheduler-service', 'scheduler-service', 8086, 'SCHEDULER-SERVICE'),
  @('cooling-controller-service', 'cooling-controller-service', 8087, 'COOLING-CONTROLLER-SERVICE'),
  @('simulator-service', 'simulator-service', 8083, 'SIMULATOR-SERVICE'),
  @('api-gateway', 'api-gateway', 8080, 'API-GATEWAY')   # port replaced by $GatewayPort below
)

function Say($m) { Write-Host "[start-all] $m" }
function Fail($m) { Write-Host "[start-all] ERROR: $m" -ForegroundColor Red; exit 1 }

function Import-DotEnv {
  $f = Join-Path $Root '.env'
  if (Test-Path $f) {
    Get-Content $f | ForEach-Object {
      if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') { Set-Item -Path "Env:$($Matches[1])" -Value $Matches[2].Trim() }
    }
  }
  foreach ($v in 'DB_URL', 'DB_USER', 'DB_PASSWORD') { Remove-Item "Env:$v" -ErrorAction SilentlyContinue }
}

function Test-Port($port) {
  $c = New-Object Net.Sockets.TcpClient
  try { $c.Connect('127.0.0.1', $port); return $true } catch { return $false } finally { $c.Dispose() }
}

function Test-EurekaUp($app) {
  try {
    $r = Invoke-WebRequest -UseBasicParsing -Headers @{ Accept = 'application/xml' } -Uri "$Eureka/eureka/apps/$app" -TimeoutSec 3
    return $r.Content -match '<status>UP</status>'
  } catch { return $false }
}

function Wait-Until($what, [scriptblock]$check, [scriptblock]$alive) {
  $waited = 0
  while (-not (& $check)) {
    if ($alive -and -not (& $alive)) { Fail "${what}: the process exited (see its log in $LogDir)" }
    Start-Sleep -Seconds 2; $waited += 2
    if ($waited -ge $ReadyTimeout) { Fail "$what not ready within ${ReadyTimeout}s" }
  }
  Say "ready: $what"
}

Import-DotEnv
$GatewayPort = if ($env:GATEWAY_PORT) { $env:GATEWAY_PORT } else { '8080' }
if ($GatewayPort -notmatch '^\d+$' -or [int]$GatewayPort -lt 1 -or [int]$GatewayPort -gt 65535) { Fail "GATEWAY_PORT must be a number between 1 and 65535, got '$GatewayPort'" }
$env:GATEWAY_PORT = $GatewayPort
$Services[$Services.Count - 1] = @('api-gateway', 'api-gateway', [int]$GatewayPort, 'API-GATEWAY')
New-Item -ItemType Directory -Force -Path $RunDir | Out-Null

if (-not $DryRun -and -not $SkipBuild) {
  Say 'building (mvnw -q -DskipTests install); use -SkipBuild to skip'
  Push-Location $Root; & .\mvnw.cmd -q -DskipTests install; $rc = $LASTEXITCODE; Pop-Location
  if ($rc -ne 0) { Fail 'build failed' }
}

if (-not $NoInfra) {
  Say '1/10 infrastructure: MySQL and RabbitMQ'
  if ($DryRun) { Say '(dry run) docker compose up -d ; wait healthy: thermogrid-mysql thermogrid-rabbitmq' }
  else {
    Push-Location $Root; docker compose --env-file .env -f infra/docker-compose.yml up -d; $rc = $LASTEXITCODE; Pop-Location
    if ($rc -ne 0) { Fail 'docker compose failed' }
    foreach ($c in 'thermogrid-mysql', 'thermogrid-rabbitmq') {
      Wait-Until "$c healthy" { (docker inspect -f '{{.State.Health.Status}}' $c 2>$null) -eq 'healthy' } $null
    }
  }
}

$i = 2
foreach ($s in $Services) {
  $name, $module, $port, $app = $s
  Say "$i/10 $name (port $port)"
  $i++
  if ($DryRun) { Say "(dry run) start $name in $module ; wait for port $port and Eureka status UP for $app"; continue }
  $pidFile = Join-Path $RunDir "$name.pid"
  $proc = $null
  if (Test-Path $pidFile) { $proc = Get-Process -Id ([int](Get-Content $pidFile)) -ErrorAction SilentlyContinue }
  if ($proc) { Say "$name already running (pid $($proc.Id)), not starting a second copy" }
  else {
    if (Test-Port $port) { $hint = if ($name -eq 'api-gateway') { " Pick a free port, for example: `$env:GATEWAY_PORT=18080; scripts\start-all.ps1 (or set GATEWAY_PORT in .env)." } else { '' }; Fail "port $port is already in use by a process this script did not start; refusing to start $name (nothing was killed).$hint" }
    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
    $log = Join-Path $LogDir "$name.log"
    $proc = Start-Process -FilePath (Join-Path $Root 'mvnw.cmd') -ArgumentList '-q', 'spring-boot:run' `
      -WorkingDirectory (Join-Path $Root $module) -RedirectStandardOutput $log -RedirectStandardError "$log.err" `
      -WindowStyle Hidden -PassThru
    Set-Content -Path $pidFile -Value $proc.Id
    Say "$name started (pid $($proc.Id)), log $log"
  }
  $p = $proc
  Wait-Until "$name port $port" { Test-Port $port } { -not $p.HasExited }
  if ($app -ne '-') { Wait-Until "$name registered in Eureka" { Test-EurekaUp $app } { -not $p.HasExited } }
}
Say "all services started. Eureka: $Eureka  Gateway: http://localhost:$GatewayPort  RabbitMQ UI: http://localhost:15672"
