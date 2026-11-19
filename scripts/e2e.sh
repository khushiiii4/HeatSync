#!/usr/bin/env bash
# End-to-end checks through the gateway (plan section 12.1 "End to end", test ids from section 12.3).
# Requires a running stack: scripts/start-all.sh and a seeded demo (POST /api/workloads/seed-demo, or this script seeds if empty).
# Secrets are read from .env / the environment and never printed. Needs only bash and curl.
#
#   scripts/e2e.sh                     run all checks against http://localhost:${GATEWAY_PORT:-8080}
#   GATEWAY_URL=http://host:port scripts/e2e.sh
# Exit code 0 = every check passed, 1 = at least one failed. Creates one LOW workload and removes it again.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi
G="${GATEWAY_URL:-http://localhost:${GATEWAY_PORT:-8080}}"
PASS=0; FAIL=0

ok()   { PASS=$((PASS+1)); printf '  PASS  %-6s %s\n' "$1" "$2"; }
bad()  { FAIL=$((FAIL+1)); printf '  FAIL  %-6s %s\n' "$1" "$2"; }
# check ID "description" expected actual
check() { if [ "$3" = "$4" ]; then ok "$1" "$2 ($4)"; else bad "$1" "$2: expected $3, got $4"; fi; }

# code METHOD URL [token] [body] [extra header]  -> prints the HTTP status only
code() {
  local m="$1" u="$2" t="${3:-}" b="${4:-}" h="${5:-}"
  local args=(-s -o /dev/null -w '%{http_code}' -X "$m" "$u")
  [ -n "$t" ] && args+=(-H "Authorization: Bearer $t")
  [ -n "$h" ] && args+=(-H "$h")
  [ -n "$b" ] && args+=(-H 'Content-Type: application/json' -d "$b")
  curl "${args[@]}" 2>/dev/null || echo 000
}
body() { # body METHOD URL token [json]  -> prints the response body
  local args=(-s -X "$1" "$2" -H "Authorization: Bearer $3")
  [ -n "${4:-}" ] && args+=(-H 'Content-Type: application/json' -d "$4")
  curl "${args[@]}" 2>/dev/null
}
login() { # login user password -> prints the token (empty on failure)
  curl -s -X POST "$G/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" 2>/dev/null | sed -n 's/.*"token":"\([^"]*\)".*/\1/p'
}
jnum() { sed -n "s/.*\"$1\":\([0-9]*\).*/\1/p" | head -1; }
jstr() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -1; }

echo "ThermoGrid end-to-end checks against $G"
for v in SEED_VIEWER_PASSWORD SEED_OPERATOR_PASSWORD SEED_ADMIN_PASSWORD; do
  [ -n "${!v:-}" ] || { echo "Missing $v in .env or the environment"; exit 1; }
done
[ "$(code GET "$G/api/platform/services")" != 000 ] || { echo "Gateway not reachable at $G"; exit 1; }

echo "== Authentication (A-01, S-01, S-02)"
VT=$(login viewer "$SEED_VIEWER_PASSWORD"); OT=$(login operator "$SEED_OPERATOR_PASSWORD"); AT=$(login admin "$SEED_ADMIN_PASSWORD")
[ -n "$VT" ] && ok A-01 "login viewer returns a token" || bad A-01 "login viewer returned no token"
[ -n "$OT" ] && ok A-01 "login operator returns a token" || bad A-01 "login operator returned no token"
[ -n "$AT" ] && ok A-01 "login admin returns a token" || bad A-01 "login admin returned no token"
check A-01 "wrong password" 401 "$(code POST "$G/api/auth/login" '' '{"username":"viewer","password":"definitely-wrong"}')"
check A-01 "unknown user" 401 "$(code POST "$G/api/auth/login" '' '{"username":"ghost","password":"x"}')"
check S-01 "call without a token" 401 "$(code GET "$G/api/topology")"
check S-02 "tampered token (signature changed)" 401 "$(code GET "$G/api/topology" "${VT%???}AAA")"
check S-02 "garbage token" 401 "$(code GET "$G/api/topology" "not.a.jwt")"
# An expired token is covered by api-gateway GatewaySecurityTest and auth JwtServiceTest; it cannot be minted without the secret here.

echo "== Roles (S-03, S-04, S-06, S-07)"
check S-03 "VIEWER POST /api/workloads" 403 "$(code POST "$G/api/workloads" "$VT" '{"name":"e2e-denied","cpuDemand":10,"priority":"LOW"}')"
check S-04 "OPERATOR POST /api/sim/control" 403 "$(code POST "$G/api/sim/control" "$OT" '{"action":"PAUSE"}')"
check S-04 "OPERATOR POST /api/workloads/seed-demo" 403 "$(code POST "$G/api/workloads/seed-demo" "$OT")"
check S-06 "forged X-User-Role ADMIN with VIEWER token" 403 "$(code POST "$G/api/sim/control" "$VT" '{"action":"PAUSE"}' 'X-User-Role: ADMIN')"
check S-07 "/internal/topology through the gateway (ADMIN)" 404 "$(code GET "$G/internal/topology" "$AT")"
check S-03 "VIEWER GET /api/topology" 200 "$(code GET "$G/api/topology" "$VT")"
ME=$(body GET "$G/api/auth/me" "$VT"); check S-06 "/api/auth/me role for viewer token" VIEWER "$(echo "$ME" | jstr role)"

echo "== Internal token (S-05) - direct calls to services"
# In the Docker deployment the service ports are not published at all: "unreachable" (000) is the strongest form of refusal.
d401() { local c; c=$(code "$@"); case "$c" in 000*) echo 401 ;; *) echo "$c" ;; esac; }
check S-05 "topology /internal/topology without token" 401 "$(d401 GET http://localhost:8082/internal/topology)"
check S-05 "topology /internal/topology wrong token" 401 "$(d401 GET http://localhost:8082/internal/topology '' '' 'X-Internal-Token: wrong')"
check S-05 "workload /internal/workloads without token" 401 "$(d401 GET http://localhost:8085/internal/workloads)"
check S-05 "telemetry /internal/telemetry/racks/latest without token" 401 "$(d401 GET http://localhost:8084/internal/telemetry/racks/latest)"
if [ -n "${THERMOGRID_INTERNAL_TOKEN:-}" ] && (exec 3<>/dev/tcp/127.0.0.1/8082) 2>/dev/null; then
  check S-05 "topology /internal/topology with the real token" 200 "$(code GET http://localhost:8082/internal/topology '' '' "X-Internal-Token: $THERMOGRID_INTERNAL_TOKEN")"
fi

echo "== One request per route prefix (A-05) and data checks (A-03)"
check A-05 "/api/topology" 200 "$(code GET "$G/api/topology" "$VT")"
check A-05 "/api/sim/state" 200 "$(code GET "$G/api/sim/state" "$VT")"
check A-05 "/api/telemetry/racks/latest" 200 "$(code GET "$G/api/telemetry/racks/latest" "$VT")"
check A-05 "/api/alerts" 200 "$(code GET "$G/api/alerts?state=ACTIVE&limit=50" "$VT")"
check A-05 "/api/workloads" 200 "$(code GET "$G/api/workloads" "$VT")"
check A-05 "/api/scheduler/status" 200 "$(code GET "$G/api/scheduler/status" "$VT")"
check A-05 "/api/cooling/units" 200 "$(code GET "$G/api/cooling/units" "$VT")"
check A-05 "/api/platform/services" 200 "$(code GET "$G/api/platform/services" "$VT")"
RACKS=$(body GET "$G/api/telemetry/racks/latest" "$VT" | grep -o '"rackId"' | wc -l | tr -d ' ')
check A-03 "telemetry latest has 16 racks" 16 "$RACKS"
check A-04 "inject failure for unknown zone" 400 "$(code POST "$G/api/sim/failures" "$OT" '{"type":"COOLING_FAILURE","targetId":"Z9","durationSec":0}')"

echo "== Workload validation (A-02) and placement flow (I-01)"
check A-02 "cpuDemand 0" 400 "$(code POST "$G/api/workloads" "$OT" '{"name":"e2e-bad","cpuDemand":0,"priority":"LOW"}')"
check A-02 "cpuDemand 101" 400 "$(code POST "$G/api/workloads" "$OT" '{"name":"e2e-bad","cpuDemand":101,"priority":"LOW"}')"
RESP=$(body POST "$G/api/workloads" "$OT" '{"name":"e2e-placement-check","cpuDemand":10,"priority":"LOW"}')
WID=$(echo "$RESP" | jnum id)
if [ -n "$WID" ]; then
  ok A-02 "valid workload created (id $WID, status $(echo "$RESP" | jstr status))"
  RACK=""; ST=""
  for _ in $(seq 1 20); do   # up to 10 s
    W=$(body GET "$G/api/workloads/$WID" "$VT"); ST=$(echo "$W" | jstr status); RACK=$(echo "$W" | jstr rackId)
    [ "$ST" = RUNNING ] && [ -n "$RACK" ] && break; sleep 0.5
  done
  if [ "$ST" = RUNNING ] && [ -n "$RACK" ]; then ok I-01 "workload $WID RUNNING on $RACK within 10 s"; else bad I-01 "workload $WID is $ST (rack '$RACK') after 10 s"; fi
  DEC=$(body GET "$G/api/scheduler/decisions?limit=50" "$VT")
  if echo "$DEC" | grep -q "\"workloadId\":$WID[,}]"; then ok I-01 "placement_decision recorded for workload $WID"; else bad I-01 "no placement decision found for workload $WID"; fi
  check I-01 "VIEWER cannot delete" 403 "$(code DELETE "$G/api/workloads/$WID" "$VT")"
  check I-01 "OPERATOR removes the test workload" 200 "$(code DELETE "$G/api/workloads/$WID" "$OT")"
else
  bad I-01 "could not create the test workload: $RESP"
fi

echo "== Dashboard-facing data (I-02, I-03 observation only)"
ZONES=$(body GET "$G/api/telemetry/zones/latest" "$VT" | grep -o '"zoneId"' | wc -l | tr -d ' ')
check I-02 "telemetry zone readings present for 4 zones" 4 "$ZONES"

echo
echo "Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
