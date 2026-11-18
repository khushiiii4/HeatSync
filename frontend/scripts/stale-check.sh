#!/usr/bin/env bash
# Manual-checklist helper for plan Phase 8 ("stopping each service produces the right stale badge").
# Usage: frontend/scripts/stale-check.sh scheduler-service     (dev server on :5173, backend started by scripts/start-all.sh)
# It opens the dashboard in headless Chrome, stops ONE service that start-all.sh started (pid file in .run/), waits for
# Eureka and the gateway to notice, records which panels degrade, restarts the service with start-all.sh, and records
# the recovery. It only touches the named service. The gateway port comes from GATEWAY_PORT (default 8080).
set -euo pipefail
SERVICE="${1:?usage: stale-check.sh <service-name, e.g. scheduler-service>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export GATEWAY_PORT="${GATEWAY_PORT:-8080}"

STOP_CMD="cd '$ROOT'; pid=\$(cat .run/$SERVICE.pid); winpid=\$(ps -p \$pid 2>/dev/null | awk 'NR==2{print \$4}'); [ -n \"\$winpid\" ] && taskkill //PID \$winpid //T //F >/dev/null 2>&1; rm -f .run/$SERVICE.pid; echo stopped $SERVICE" \
START_CMD="cd '$ROOT'; SKIP_BUILD=1 bash scripts/start-all.sh >/dev/null 2>&1; echo restarted $SERVICE" \
  node "$ROOT/frontend/scripts/snapshot.mjs"
