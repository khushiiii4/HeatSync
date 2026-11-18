#!/usr/bin/env bash
# Stops only the processes scripts/start-all.sh started (pid files in .run/), in reverse start order.
# It never touches other processes and never stops the Docker containers (MySQL and RabbitMQ keep their data);
# use "docker compose -f infra/docker-compose.yml stop" for those.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/.run"
ORDER=(api-gateway simulator-service cooling-controller-service scheduler-service workload-service telemetry-service topology-service eureka-server)

stop_tree() {   # stop_tree PID : stop the mvnw wrapper and its java child
  local pid="$1"
  if command -v taskkill >/dev/null 2>&1; then
    local winpid; winpid="$(ps -p "$pid" 2>/dev/null | awk 'NR==2{print $4}')"
    [ -n "$winpid" ] && taskkill //PID "$winpid" //T //F >/dev/null 2>&1
  else
    pkill -TERM -P "$pid" 2>/dev/null
    kill -TERM "$pid" 2>/dev/null
  fi
  kill -0 "$pid" 2>/dev/null && kill "$pid" 2>/dev/null
  return 0
}

for name in "${ORDER[@]}"; do
  pidfile="$RUN_DIR/$name.pid"
  [ -f "$pidfile" ] || continue
  pid="$(cat "$pidfile")"
  if kill -0 "$pid" 2>/dev/null; then
    printf '[stop-all] stopping %s (pid %s)\n' "$name" "$pid"
    stop_tree "$pid"
  else
    printf '[stop-all] %s is not running (stale pid file removed)\n' "$name"
  fi
  rm -f "$pidfile"
done
