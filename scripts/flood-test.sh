#!/usr/bin/env bash
# Telemetry flood test (plan 12.3 P-02): publishes synthetic rack readings through the simulator's flood generator,
# watches the RabbitMQ queue q.telemetry.rack-readings and reports peak depth, drain time and rows stored.
# Run it once per instance count (scale telemetry-service first) and record the numbers in docs/performance.md.
#
#   BASE_URL=http://localhost:8088 RABBITMQ_API=http://localhost:15673 scripts/flood-test.sh
#   RATE=500 SECS=60 RACKS=50   tunables (defaults shown)
# Needs bash, curl, node, and .env (admin password, RabbitMQ credentials). Cleans up its synthetic racks at the end.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi
BASE="${BASE_URL:-http://localhost:${HTTP_PORT:-80}}"
RMQ="${RABBITMQ_API:-http://localhost:15673}"
RATE="${RATE:-500}"; SECS="${SECS:-60}"; RACKS="${RACKS:-50}"
Q="q.telemetry.rack-readings"
OUT="${OUT:-/tmp/flood-timeline.csv}"

j() { node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{try{const o=JSON.parse(s);const r=($1);console.log(r===undefined?'':r)}catch(e){console.log('')}})"; }
TOKEN=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$SEED_ADMIN_PASSWORD\"}" | j 'o.token')
[ -n "$TOKEN" ] || { echo "login failed at $BASE"; exit 1; }
api()  { curl -s -m 10 -H "Authorization: Bearer $TOKEN" "$BASE$1"; }
qdepth() { curl -s -m 5 -u "$RABBITMQ_USER:$RABBITMQ_PASSWORD" "$RMQ/api/queues/%2F/$Q" | j 'o.messages'; }
instances() { api /api/platform/services | j "(o.find(s=>s.name==='TELEMETRY-SERVICE')||{}).instances"; }

INST=$(instances)
echo "Flood test: $RATE msg/s for $SECS s over $RACKS synthetic racks, telemetry instances: $INST"
api /api/sim/flood | j 'JSON.stringify(o)' | sed 's/^/  before: /'
START=$(date +%s)
RESP=$(curl -s -m 10 -w '\n%{http_code}' -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"ratePerSec\":$RATE,\"durationSec\":$SECS,\"racks\":$RACKS}" "$BASE/api/sim/flood")
CODE=$(echo "$RESP" | tail -1); [ "$CODE" = 202 ] || { echo "flood not accepted (HTTP $CODE): $(echo "$RESP" | head -1)"; exit 1; }

echo "t_sec,queue_depth" > "$OUT"
PEAK=0; END_AT=""; DRAIN_AT=""
while :; do
  NOW=$(( $(date +%s) - START )); D=$(qdepth); D=${D:-0}
  echo "$NOW,$D" >> "$OUT"
  [ "$D" -gt "$PEAK" ] && PEAK=$D
  RUNNING=$(api /api/sim/flood | j 'o.running')
  if [ "$RUNNING" = false ] && [ -z "$END_AT" ]; then END_AT=$NOW; fi
  if [ -n "$END_AT" ] && [ "$D" -le 20 ]; then DRAIN_AT=$NOW; break; fi
  [ "$NOW" -gt $(( SECS + 600 )) ] && { echo "queue did not drain within 10 min"; break; }
  sleep 2
done
FIN=$(api /api/sim/flood)
SENT=$(echo "$FIN" | j 'o.sent'); FAILED=$(echo "$FIN" | j 'o.failed')
sleep 3
REMOVED=$(curl -s -m 60 -X DELETE -H "Authorization: Bearer $TOKEN" "$BASE/api/telemetry/synthetic")
STORED=$(echo "$REMOVED" | j 'o.readings')
echo "--- result ---"
echo "telemetry instances      : $INST"
echo "messages sent / failed   : $SENT / $FAILED (planned $((RATE*SECS)))"
echo "flood generator finished : t=${END_AT}s (planned ${SECS}s)"
echo "peak queue depth         : $PEAK messages"
echo "queue drained (<=20)     : t=${DRAIN_AT:-n/a}s, i.e. $(( ${DRAIN_AT:-0} - ${END_AT:-0} )) s after the last message was sent"
echo "rows stored (synthetic)  : $STORED (removed by DELETE /api/telemetry/synthetic: $REMOVED)"
echo "timeline written to      : $OUT"
