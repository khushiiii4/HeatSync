#!/usr/bin/env bash
# Failure and recovery scenarios (plan section 10 scenarios 1 to 9, section 12.3 F-02 and F-03, roadmap Phase 11).
# Drives the RUNNING stack through the gateway and asserts the documented behaviour with time limits.
#
#   scripts/failure-tests.sh                 run every scenario once
#   SCENARIOS="s6 s9" scripts/failure-tests.sh   run only some (s1 s2 s6 s7 s9 f02 f03)
#   SKIP_BROKER_RESTART=1 ...                skip f03 (it restarts the thermogrid-rabbitmq container)
# Needs: bash, curl, node (JSON parsing), docker (f03), a running stack from scripts/start-all.sh, and .env.
# Secrets come from .env and are never printed. Every scenario starts from a reset (failures cleared, all workloads
# removed, simulator RESET, demo seed re-applied). Exit code 0 = all assertions passed.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi
G="${GATEWAY_URL:-http://localhost:${GATEWAY_PORT:-8080}}"
RMQ="${RABBITMQ_API:-http://localhost:15672}"
SCENARIOS="${SCENARIOS:-s1 s2 s6 s7 s9 f02 f03}"
BASELINE_SECS="${BASELINE_SECS:-45}"
DEGRADE_PARAM="${DEGRADE_PARAM:-0.15}"   # remaining efficiency for the Z2 degradation (plan section 10 says 0.25, which only reaches WATCH with the controller running: D-093)
PASS=0; FAIL=0; T=""
Z3_RACKS="R31 R32 R33 R34"

log()  { printf '%s\n' "$*"; }
ok()   { PASS=$((PASS+1)); printf '  PASS  %s\n' "$*"; }
bad()  { FAIL=$((FAIL+1)); printf '  FAIL  %s\n' "$*"; }
now()  { date +%s; }

# j 'js expression on o' : reads JSON from stdin, prints the expression (empty on a parse failure)
j() { node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{try{const o=JSON.parse(s);const r=($1);console.log(r===undefined?'':r)}catch(e){console.log('')}})"; }
login() { curl -s -X POST "$G/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$SEED_ADMIN_PASSWORD\"}" | j 'o.token'; }
api()  { curl -s -m 8 -H "Authorization: Bearer $T" "$G$1"; }                       # api PATH
send() { curl -s -m 8 -o /dev/null -w '%{http_code}' -X "$1" -H "Authorization: Bearer $T" \
  ${3:+-H 'Content-Type: application/json' -d "$3"} "$G$2"; }                     # send METHOD PATH [json]
post() { curl -s -m 8 -X POST -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d "$2" "$G$1"; }
rmq()  { curl -s -m 5 -u "$RABBITMQ_USER:$RABBITMQ_PASSWORD" "$RMQ/api/queues/%2F/$1" | j 'o.messages'; }

# wait_for "description" timeout_secs 'shell condition'  -> PASS with elapsed time, or FAIL
wait_for() {
  local desc="$1" limit="$2" cond="$3" start; start=$(now)
  while :; do
    if eval "$cond" >/dev/null 2>&1; then ok "$desc ($(( $(now) - start )) s, limit $limit s)"; return 0; fi
    [ $(( $(now) - start )) -ge "$limit" ] && { bad "$desc (not within $limit s)"; return 1; }
    sleep 2
  done
}
holds_for() {   # holds_for "description" secs 'condition' : condition must stay true for the whole period
  local desc="$1" secs="$2" cond="$3" start; start=$(now)
  while [ $(( $(now) - start )) -lt "$secs" ]; do
    eval "$cond" >/dev/null 2>&1 || { bad "$desc (broke after $(( $(now) - start )) s)"; return 1; }
    sleep 2
  done
  ok "$desc (held $secs s)"
}

# ---- state helpers --------------------------------------------------------------------------------------------
svc_up()      { api /api/platform/services | j "(o.find(s=>s.name==='$1')||{}).status==='UP'?'y':''" | grep -q y; }
svc_down()    { api /api/platform/services | j "(o.find(s=>s.name==='$1')||{}).status==='DOWN'?'y':''" | grep -q y; }
all_up()      { [ "$(api /api/platform/services | j "o.filter(s=>s.status==='UP').length")" = 9 ] || [ "$(api /api/platform/services | j "o.filter(s=>s.status==='UP').length")" = 8 ]; }
active_alerts() { api '/api/alerts?state=ACTIVE&limit=50' | j 'o.length'; }
sched_mode()  { api /api/scheduler/status | j 'o.mode'; }
pending()     { api /api/scheduler/status | j 'o.pendingWorkloads'; }
cool()        { api /api/cooling/units | j "(o.find(u=>u.zoneId==='$1')||{})['$2']"; }   # cool Z2 outputPct
applied()     { api '/api/scheduler/migrations?limit=200' | j "o.filter(m=>m.status==='APPLIED'&&/THERMAL|COOLING/.test(m.reason)).length"; }
rack_field()  { api /api/telemetry/racks/latest | j "(o.find(r=>r.rackId==='$1')||{})['$2']"; }
newest_reading() { api /api/telemetry/racks/latest | j "o.map(r=>r.updatedAt).sort().pop()"; }
reading_age() { node -e "const t=Date.parse('$(newest_reading)');console.log(isNaN(t)?999999:Math.round((Date.now()-t)/1000))"; }
z3_load()     { api '/api/workloads?status=RUNNING' | j "o.filter(w=>/^R3[1-4]$/.test(w.rackId)).length"; }
running_on()  { api "/api/workloads/$1" | j "o.status==='RUNNING'?o.rackId:''"; }
inject()      { post /api/sim/failures "$1" | j 'o.id'; }                                # inject JSON -> id
clear_failure() { send DELETE "/api/sim/failures/$1" >/dev/null; }
clear_all_failures() { for id in $(api '/api/sim/failures?active=true' | j 'o.map(f=>f.id).join(" ")'); do clear_failure "$id"; done; }

reset_demo() {
  log "-- reset: clear failures, remove workloads, RESET simulator, seed demo"
  clear_all_failures
  for id in $(api '/api/workloads' | j "o.filter(w=>w.status!=='REMOVED').map(w=>w.id).join(' ')"); do send DELETE "/api/workloads/$id" >/dev/null; done
  post /api/sim/control '{"action":"RESET"}' >/dev/null
  sleep 3
  post /api/scheduler/autopilot '{"enabled":true}' >/dev/null
  send POST /api/workloads/seed-demo >/dev/null
  wait_for "reset settled: no active alerts, scheduler NORMAL, nothing pending" 180 \
    '[ "$(active_alerts)" = 0 ] && [ "$(sched_mode)" = NORMAL ] && [ "$(pending)" = 0 ]'
}
new_workload() { post /api/workloads "{\"name\":\"$1\",\"cpuDemand\":10,\"priority\":\"LOW\"}" | j 'o.id'; }

# ---- scenarios -------------------------------------------------------------------------------------------------
s1() {
  log "== Scenario 1: normal operation with balanced workloads"
  reset_demo; sleep "$BASELINE_SECS"
  [ "$(api /api/telemetry/racks/latest | j "o.filter(r=>r.riskLevel==='NORMAL').length")" = 16 ] && ok "16 racks NORMAL" || bad "not all 16 racks NORMAL"
  [ "$(active_alerts)" = 0 ] && ok "no active alerts" || bad "active alerts present"
  [ "$(pending)" = 0 ] && ok "no pending workloads" || bad "pending workloads"
  [ "$(api /api/cooling/units | j "o.filter(u=>u.outputPct<=60).length")" = 4 ] && ok "cooling outputs at or below 60 percent" || bad "cooling output above 60 in a zone"
  all_up && ok "all services UP" || bad "a service is not UP"
}

s2() {
  log "== Scenarios 2 to 5: degraded cooling in Z2 -> predicted alert -> cooling boost -> migration -> clear -> release"
  reset_demo
  local before id; before=$(applied)
  id=$(inject '{"type":"COOLING_DEGRADED","targetId":"Z2","param":'"$DEGRADE_PARAM"',"durationSec":0}')
  [ -n "$id" ] && ok "failure injected (id $id)" || { bad "injection failed"; return; }
  wait_for "S3 alert raised in Z2 (WARNING or CRITICAL)" 200 \
    '[ -n "$(api "/api/alerts?state=ACTIVE&limit=50" | j "o.filter(a=>a.zoneId===\"Z2\"&&/WARNING|CRITICAL/.test(a.level)).length>0?\"y\":\"\"")" ]'
  [ -n "$(api '/api/alerts?state=ALL&limit=50' | j "o.filter(a=>a.zoneId==='Z2'&&a.basis==='PREDICTED').length>0?'y':''")" ] \
    && ok "S3 at least one Z2 alert has basis PREDICTED" || bad "S3 no PREDICTED alert for Z2"
  log "  peak Z2 rack temperature so far: $(api /api/telemetry/racks/latest | j "Math.max(...o.filter(r=>r.zoneId===\"Z2\").map(r=>r.tempC))") C (DEGRADE_PARAM=$DEGRADE_PARAM)"
  wait_for "S2 Z2 cooling output at least 90 percent" 120 '[ "$(cool Z2 outputPct)" -ge 90 ]'
  wait_for "S4 scheduler applied at least one thermal migration" 200 '[ "$(applied)" -gt "$before" ]'
  clear_failure "$id"; ok "failure cleared"
  wait_for "S5 all alerts cleared after the failure is cleared" 400 '[ "$(active_alerts)" = 0 ]'
  wait_for "S5 Z2 output released to 60 percent or less" 200 '[ "$(cool Z2 outputPct)" -le 60 ]'
}

s6() {
  log "== Scenario 6: cooling unit failure in Z3"
  reset_demo
  local id t0; t0=$(date -u +%Y-%m-%dT%H:%M:%S)
  id=$(inject '{"type":"COOLING_FAILURE","targetId":"Z3","durationSec":0}')
  [ -n "$id" ] && ok "failure injected (id $id)" || { bad "injection failed"; return; }
  wait_for "Z3 unit health FAULT" 30 '[ "$(cool Z3 health)" = FAULT ]'
  wait_for "healthy zones raise their floor to 60" 40 \
    '[ "$(api /api/cooling/units | j "o.filter(u=>u.zoneId!==\"Z3\"&&u.floorPct>=60&&u.outputPct>=60).length")" = 3 ]'
  wait_for "scheduler sees Z3 as FAULT" 30 '[ "$(api /api/scheduler/status | j "(o.zones.find(z=>z.zoneId===\"Z3\")||{}).coolingHealth")" = FAULT ]'
  wait_for "Z3 drained (no running workload on R31 to R34)" 150 '[ "$(z3_load)" = 0 ]'
  [ "$(api '/api/alerts?state=ALL&limit=200' | j "o.filter(a=>a.level==='CRITICAL'&&a.raisedAt>='$t0').length")" = 0 ] \
    && ok "no CRITICAL alert during the drain" || bad "a CRITICAL alert was raised during the drain"
  clear_failure "$id"
  wait_for "Z3 unit back to OK" 40 '[ "$(cool Z3 health)" = OK ]'
  wait_for "floors released to 30" 80 '[ "$(api /api/cooling/units | j "o.filter(u=>u.floorPct===30).length")" = 4 ]'
}

s7() {
  log "== Scenarios 7 and 8: scheduler failure and recovery"
  reset_demo
  local id wid; id=$(inject '{"type":"COOLING_DEGRADED","targetId":"Z2","param":'"$DEGRADE_PARAM"',"durationSec":0}')
  wait_for "alert active in Z2 before the scheduler is stopped" 200 '[ "$(active_alerts)" -ge 1 ]'
  "$ROOT/scripts/stop-all.sh" scheduler-service >/dev/null 2>&1
  wait_for "S7 Eureka drops SCHEDULER-SERVICE (platform shows DOWN)" 40 'svc_down SCHEDULER-SERVICE'
  wait_for "S7 Z2 cooling stays AUTO at 90 percent or more" 90 '[ "$(cool Z2 mode)" = AUTO ] && [ "$(cool Z2 outputPct)" -ge 90 ]'
  [ "$(active_alerts)" -ge 1 ] && ok "S7 alerts stay active" || bad "S7 alerts vanished with the scheduler down"
  local r1 r2; r1=$(newest_reading); sleep 6; r2=$(newest_reading)
  [ "$r1" != "$r2" ] && ok "S7 heat map data keeps updating" || bad "S7 telemetry stopped updating"
  wid=$(new_workload "failure-test-pending")
  sleep 15
  [ "$(api "/api/workloads/$wid" | j 'o.status')" = PENDING ] && ok "S7 new workload stays PENDING" || bad "S7 workload was placed without a scheduler"
  log "-- starting scheduler-service again"
  SKIP_BUILD=1 START_INFRA=0 "$ROOT/scripts/start-all.sh" >/tmp/failure-restart.log 2>&1
  wait_for "S8 SCHEDULER-SERVICE back UP" 120 'svc_up SCHEDULER-SERVICE'
  wait_for "S8 pending workload gets placed" 90 '[ -n "$(running_on "$wid")" ]'
  wait_for "S8 pending count returns to 0" 60 '[ "$(pending)" = 0 ]'
  clear_failure "$id"
  wait_for "S8 alerts clear after recovery" 400 '[ "$(active_alerts)" = 0 ]'
  send DELETE "/api/workloads/$wid" >/dev/null
}

s9() {
  log "== Scenario 9: telemetry failure (HOLD mode, queue backlog, replay)"
  reset_demo
  local dlq0 wid; dlq0=$(rmq thermogrid.dlq)
  "$ROOT/scripts/stop-all.sh" telemetry-service >/dev/null 2>&1
  wait_for "TELEMETRY-SERVICE DOWN in the platform status" 40 'svc_down TELEMETRY-SERVICE'
  wait_for "scheduler enters HOLD" 90 '[ "$(sched_mode)" = HOLD ]'
  wait_for "q.telemetry.rack-readings backlog grows past 100" 60 '[ "$(rmq q.telemetry.rack-readings)" -gt 100 ]'
  local s1 s2; s1=$(cool Z1 seq); sleep 12; s2=$(cool Z1 seq)
  [ "$s1" != "$s2" ] && ok "cooling keeps publishing without telemetry (seq $s1 -> $s2)" || bad "cooling state stopped changing"
  wid=$(new_workload "failure-test-hold")
  wait_for "HOLD mode still places a new workload (CPU only)" 60 '[ -n "$(running_on "$wid")" ]'
  log "-- starting telemetry-service again"
  SKIP_BUILD=1 START_INFRA=0 "$ROOT/scripts/start-all.sh" >/tmp/failure-restart.log 2>&1
  wait_for "TELEMETRY-SERVICE back UP" 120 'svc_up TELEMETRY-SERVICE'
  wait_for "backlog replays (queue below 50)" 180 '[ "$(rmq q.telemetry.rack-readings)" -lt 50 ]'
  wait_for "scheduler returns to NORMAL" 120 '[ "$(sched_mode)" = NORMAL ]'
  wait_for "fresh rack readings (newest under 10 s old)" 60 '[ "$(reading_age)" -lt 10 ]'
  [ "$(rmq thermogrid.dlq)" = "$dlq0" ] && ok "no new dead letters (dlq stays at $dlq0)" || bad "dead-letter queue changed ($dlq0 -> $(rmq thermogrid.dlq))"
  send DELETE "/api/workloads/$wid" >/dev/null
}

f02() {
  log "== F-02: sensor dropout on R11"
  reset_demo
  local id wid1 wid2; id=$(inject '{"type":"SENSOR_DROPOUT","targetId":"R11","durationSec":0}')
  [ -n "$id" ] && ok "failure injected (id $id)" || { bad "injection failed"; return; }
  wait_for "R11 sensor STALE" 40 '[ "$(rack_field R11 sensorStatus)" = STALE ]'
  wid1=$(new_workload "failure-test-stale-a"); wid2=$(new_workload "failure-test-stale-b")
  wait_for "both new workloads placed" 60 '[ -n "$(running_on "$wid1")" ] && [ -n "$(running_on "$wid2")" ]'
  [ "$(running_on "$wid1")" != R11 ] && [ "$(running_on "$wid2")" != R11 ] && ok "no new work placed on the STALE rack" || bad "work was placed on the STALE rack"
  clear_failure "$id"
  wait_for "R11 sensor back to OK" 40 '[ "$(rack_field R11 sensorStatus)" = OK ]'
  send DELETE "/api/workloads/$wid1" >/dev/null; send DELETE "/api/workloads/$wid2" >/dev/null
}

f03() {
  log "== F-03: RabbitMQ restart (the dev container thermogrid-rabbitmq is restarted; no service is restarted)"
  [ "${SKIP_BROKER_RESTART:-0}" = 1 ] && { log "  skipped (SKIP_BROKER_RESTART=1)"; return; }
  reset_demo
  local dlq0 wid; dlq0=$(rmq thermogrid.dlq)
  docker restart thermogrid-rabbitmq >/dev/null 2>&1 && ok "broker container restarted" || { bad "docker restart failed"; return; }
  wait_for "broker answers again" 90 '[ -n "$(rmq q.telemetry.rack-readings)" ]'
  wait_for "fresh rack readings resume without restarting any service" 120 '[ "$(reading_age)" -lt 10 ]'
  wait_for "cooling state keeps flowing (zone cards update)" 60 \
    '[ "$(api /api/cooling/units | j "o.filter(u=>Date.now()-Date.parse(u.updatedAt)<20000||Date.now()-Date.parse(u.lastReadingAt)<20000).length")" = 4 ]'
  wid=$(new_workload "failure-test-after-broker")
  wait_for "a new workload is placed after the broker restart" 60 '[ -n "$(running_on "$wid")" ]'
  [ "$(rmq thermogrid.dlq)" = "$dlq0" ] && ok "no new dead letters (dlq stays at $dlq0)" || bad "dead-letter queue changed ($dlq0 -> $(rmq thermogrid.dlq))"
  send DELETE "/api/workloads/$wid" >/dev/null
}

# ---- main ------------------------------------------------------------------------------------------------------
for v in SEED_ADMIN_PASSWORD RABBITMQ_USER RABBITMQ_PASSWORD; do [ -n "${!v:-}" ] || { echo "Missing $v in .env"; exit 1; }; done
T=$(login); [ -n "$T" ] || { echo "Cannot log in through $G"; exit 1; }
log "ThermoGrid failure tests against $G (scenarios: $SCENARIOS) $(date '+%F %T')"
for s in $SCENARIOS; do
  T=$(login); "$s"
done
clear_all_failures
log ""; log "Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
