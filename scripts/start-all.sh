#!/usr/bin/env bash
# Starts ThermoGrid for local development in the order required by plan section 13 (consumers before producers).
#
#   1. MySQL and RabbitMQ (Docker)        5. workload-service
#   2. eureka-server                      6. scheduler-service
#   3. topology-service                   7. cooling-controller-service
#   4. telemetry-service                  8. simulator-service   9. api-gateway
#
# Why this order matters: a message published before its queue exists is lost (RabbitMQ drops it when no queue is
# bound), and every consumer declares and owns its queues. The simulator is the heaviest producer and needs topology
# and workload-service, so it starts after the consumers of its events. Workload-service only publishes when asked.
#
# Usage:   scripts/start-all.sh            start everything (skips services that already run)
#          DRY_RUN=1 scripts/start-all.sh  print the plan and the readiness checks, start nothing
#          SKIP_BUILD=1 scripts/start-all.sh  do not run "mvnw install" first
#          START_INFRA=0 scripts/start-all.sh do not touch Docker (assume MySQL and RabbitMQ run)
#          LIMIT=2 scripts/start-all.sh       start only the first N services (for testing the readiness logic)
# Stop:    scripts/stop-all.sh   (stops only the processes this script started, recorded in .run/)
#
# Secrets: read from the environment or from .env (git-ignored). Nothing is hard-coded here. Per-service database
# settings (DB_URL, DB_USER, DB_PASSWORD) are unset so every service uses its own <SERVICE>_DB_PASSWORD.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/.run"
LOG_DIR="$ROOT/.run/logs"
DRY_RUN="${DRY_RUN:-0}"
SKIP_BUILD="${SKIP_BUILD:-0}"
START_INFRA="${START_INFRA:-1}"
READY_TIMEOUT="${READY_TIMEOUT:-180}"
LIMIT="${LIMIT:-0}"
EUREKA_URL_BASE="${EUREKA_BASE:-http://localhost:8761}"

# name | module | port | eureka application name ("-" = not registered)
SERVICES=(
  "eureka-server|eureka-server|8761|-"
  "topology-service|topology-service|8082|TOPOLOGY-SERVICE"
  "telemetry-service|telemetry-service|8084|TELEMETRY-SERVICE"
  "workload-service|workload-service|8085|WORKLOAD-SERVICE"
  "scheduler-service|scheduler-service|8086|SCHEDULER-SERVICE"
  "cooling-controller-service|cooling-controller-service|8087|COOLING-CONTROLLER-SERVICE"
  "simulator-service|simulator-service|8083|SIMULATOR-SERVICE"
  "api-gateway|api-gateway|8080|API-GATEWAY"
)

say()  { printf '[start-all] %s\n' "$*"; }
die()  { printf '[start-all] ERROR: %s\n' "$*" >&2; exit 1; }

load_env() {
  if [ -f "$ROOT/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    . "$ROOT/.env"
    set +a
  fi
  unset DB_URL DB_USER DB_PASSWORD
}

port_open() {   # port_open PORT
  (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && return 0 || return 1
}

pid_alive() { [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null; }

wait_for() {    # wait_for DESCRIPTION TIMEOUT COMMAND...   (COMMAND must succeed when ready)
  local what="$1" timeout="$2"; shift 2
  local waited=0
  until "$@"; do
    sleep 2; waited=$((waited + 2))
    if [ "$waited" -ge "$timeout" ]; then
      return 1
    fi
  done
  say "ready: $what"
}

docker_health() { [ "$(docker inspect -f '{{.State.Health.Status}}' "$1" 2>/dev/null)" = "healthy" ]; }

eureka_up() {   # eureka_up APP
  curl -fsS -H 'Accept: application/xml' "$EUREKA_URL_BASE/eureka/apps/$1" 2>/dev/null | grep -q '<status>UP</status>'
}

start_infra() {
  if [ "$START_INFRA" != "1" ]; then say "START_INFRA=0: assuming MySQL and RabbitMQ are running"; return; fi
  say "1/9 infrastructure: MySQL and RabbitMQ"
  if [ "$DRY_RUN" = "1" ]; then
    say "(dry run) docker compose --env-file .env -f infra/docker-compose.yml up -d ; wait healthy: thermogrid-mysql thermogrid-rabbitmq"
    return
  fi
  command -v docker >/dev/null 2>&1 || die "docker is not installed or not on PATH"
  ( cd "$ROOT" && docker compose --env-file .env -f infra/docker-compose.yml up -d ) || die "docker compose failed"
  wait_for "MySQL healthy" "$READY_TIMEOUT" docker_health thermogrid-mysql || die "MySQL did not become healthy in ${READY_TIMEOUT}s"
  wait_for "RabbitMQ healthy" "$READY_TIMEOUT" docker_health thermogrid-rabbitmq || die "RabbitMQ did not become healthy in ${READY_TIMEOUT}s"
}

start_service() {   # start_service INDEX NAME MODULE PORT EUREKA_APP
  local idx="$1" name="$2" module="$3" port="$4" app="$5"
  local pidfile="$RUN_DIR/$name.pid" log="$LOG_DIR/$name.log"
  say "$idx/9 $name (port $port)"
  if [ "$DRY_RUN" = "1" ]; then
    say "(dry run) start $name in $module/ ; wait for port $port$([ "$app" != "-" ] && printf ' and Eureka status UP for %s' "$app")"
    return
  fi
  if [ -f "$pidfile" ] && pid_alive "$(cat "$pidfile")"; then
    say "$name already running (pid $(cat "$pidfile")), not starting a second copy"
  else
    rm -f "$pidfile"
    if port_open "$port"; then
      die "port $port is already in use by a process this script did not start; refusing to start $name (stop it first, nothing was killed)"
    fi
    mkdir -p "$LOG_DIR"
    # Run from the module directory (the simulator keeps its H2 file relative to the working directory).
    ( cd "$ROOT/$module" && exec "$ROOT/mvnw" -q spring-boot:run ) >"$log" 2>&1 &
    echo $! >"$pidfile"
    say "$name started (pid $(cat "$pidfile")), log $log"
  fi
  local pid; pid="$(cat "$pidfile")"
  local waited=0
  until port_open "$port"; do
    pid_alive "$pid" || { tail -n 25 "$log" >&2; die "$name exited before it opened port $port (log above)"; }
    sleep 2; waited=$((waited + 2))
    [ "$waited" -ge "$READY_TIMEOUT" ] && { tail -n 25 "$log" >&2; die "$name did not open port $port within ${READY_TIMEOUT}s"; }
  done
  if [ "$app" != "-" ]; then
    waited=0
    until eureka_up "$app"; do
      pid_alive "$pid" || { tail -n 25 "$log" >&2; die "$name exited before it registered with Eureka (log above)"; }
      sleep 2; waited=$((waited + 2))
      [ "$waited" -ge "$READY_TIMEOUT" ] && { tail -n 25 "$log" >&2; die "$name is not UP in Eureka after ${READY_TIMEOUT}s"; }
    done
  fi
  say "ready: $name"
}

main() {
  load_env
  mkdir -p "$RUN_DIR"
  if [ "$DRY_RUN" != "1" ] && [ "$SKIP_BUILD" != "1" ]; then
    say "building (mvnw -q -DskipTests install); set SKIP_BUILD=1 to skip"
    ( cd "$ROOT" && ./mvnw -q -DskipTests install ) || die "build failed"
  fi
  start_infra
  local i=2
  for entry in "${SERVICES[@]}"; do
    if [ "$LIMIT" -gt 0 ] && [ $((i - 1)) -gt "$LIMIT" ]; then say "LIMIT=$LIMIT reached, stopping the start sequence"; break; fi
    IFS='|' read -r name module port app <<<"$entry"
    start_service "$i" "$name" "$module" "$port" "$app"
    i=$((i + 1))
  done
  say "all services started. Eureka: $EUREKA_URL_BASE  Gateway: http://localhost:8080  RabbitMQ UI: http://localhost:15672"
  say "next: POST /api/workloads/seed-demo once every service shows UP"
}

main "$@"
