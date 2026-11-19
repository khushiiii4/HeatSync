#!/usr/bin/env bash
# Resets a RUNNING ThermoGrid to the initial demo state (plan section 10 "Reset between runs", roadmap phase 13).
# It only uses the public API as ADMIN. It does not stop services, delete databases or touch users.
#
#   scripts/reset-demo.sh                  reset and wait until the system is calm
#   BASE_URL=http://localhost:8088 scripts/reset-demo.sh     (Docker deployment behind nginx)
#   scripts/reset-demo.sh --dry-run        show what would be done, change nothing
#
# What it does, in order:
#   1. checks that all 8 services are UP (it does NOT start missing ones; it tells you how)
#   2. clears every active failure (cooling failures, degradations, sensor dropouts, heat spikes)
#   3. removes all workloads, then re-applies the demo seed (about 36 workloads, 720 CPU units, Z2 the warm zone)
#   4. removes leftover load-test racks (DELETE /api/telemetry/synthetic)
#   5. RESETs the simulator (temperatures back to start) and switches the autopilot on
#   6. waits until: no active alerts, scheduler mode NORMAL, nothing pending, 16 racks NORMAL
# What it keeps: alert, migration and cooling-action HISTORY (the Events page still shows earlier runs).
# Needs bash, curl, node (JSON parsing) and .env with SEED_ADMIN_PASSWORD. Exit 0 = calm state reached.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi
BASE="${BASE_URL:-http://localhost:${GATEWAY_PORT:-8080}}"
WAIT_SECS="${WAIT_SECS:-120}"
DRY=0; [ "${1:-}" = "--dry-run" ] && DRY=1
START=$(date +%s)
say() { printf '[reset-demo] %s\n' "$*"; }
die() { printf '[reset-demo] ERROR: %s\n' "$*" >&2; exit 1; }

j() { node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{try{const o=JSON.parse(s);const r=($1);console.log(r===undefined?'':r)}catch(e){console.log('')}})"; }
[ -n "${SEED_ADMIN_PASSWORD:-}" ] || die "SEED_ADMIN_PASSWORD is not set (put it in .env)"
TOKEN=$(curl -s -m 8 -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$SEED_ADMIN_PASSWORD\"}" | j 'o.token')
[ -n "$TOKEN" ] || die "cannot log in as admin at $BASE (is the gateway up? is BASE_URL right?)"
get()  { curl -s -m 10 -H "Authorization: Bearer $TOKEN" "$BASE$1"; }
call() { curl -s -m 20 -o /dev/null -w '%{http_code}' -X "$1" -H "Authorization: Bearer $TOKEN" \
  ${3:+-H 'Content-Type: application/json' -d "$3"} "$BASE$2"; }

DOWN=$(get /api/platform/services | j "o.filter(s=>s.status!=='UP').map(s=>s.name).join(', ')")
if [ -n "$DOWN" ]; then
  say "These services are not UP: $DOWN"
  say "Start them first: scripts/start-all.sh (it skips services that already run), or in Docker:"
  say "  docker compose --env-file .env -f infra/docker-compose.full.yml up -d"
  die "reset needs every service running"
fi
say "all services UP at $BASE"

if [ "$DRY" = 1 ]; then
  say "dry run: would clear $(get '/api/sim/failures?active=true' | j 'o.length') active failure(s), remove $(get /api/workloads | j "o.filter(w=>w.status!=='REMOVED').length") workload(s), re-seed, RESET the simulator, autopilot on"
  exit 0
fi

for id in $(get '/api/sim/failures?active=true' | j 'o.map(f=>f.id).join(" ")'); do call DELETE "/api/sim/failures/$id" >/dev/null; done
say "failures cleared"
for id in $(get /api/workloads | j "o.filter(w=>w.status!=='REMOVED').map(w=>w.id).join(' ')"); do call DELETE "/api/workloads/$id" >/dev/null; done
call DELETE /api/telemetry/synthetic >/dev/null
call POST /api/sim/control '{"action":"RESET"}' >/dev/null
sleep 3
call POST /api/scheduler/autopilot '{"enabled":true}' >/dev/null
SEED=$(call POST /api/workloads/seed-demo)
say "simulator reset, autopilot on, demo seed applied (HTTP $SEED)"

calm() {
  [ "$(get '/api/alerts?state=ACTIVE&limit=50' | j 'o.length')" = 0 ] &&
  [ "$(get /api/scheduler/status | j 'o.mode')" = NORMAL ] &&
  [ "$(get /api/scheduler/status | j 'o.pendingWorkloads')" = 0 ] &&
  [ "$(get /api/telemetry/racks/latest | j "o.filter(r=>r.riskLevel==='NORMAL'&&r.sensorStatus==='OK').length")" = 16 ]
}
while ! calm; do
  [ $(( $(date +%s) - START )) -ge "$WAIT_SECS" ] && die "not calm after $WAIT_SECS s (alerts, HOLD mode, pending work or a non-NORMAL rack remain); check the Overview"
  sleep 2
done
RUNNING=$(get /api/workloads/summary | j 'o.runningCount'); CPU=$(get /api/workloads/summary | j 'o.totalCpuUnits')
say "calm: 16 racks NORMAL, no alerts, scheduler NORMAL, $RUNNING workloads running ($CPU CPU units). Took $(( $(date +%s) - START )) s."
