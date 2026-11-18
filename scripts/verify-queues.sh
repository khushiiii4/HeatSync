#!/usr/bin/env bash
# Read-only check of the live RabbitMQ queues against plan 8.4 / docs/messaging.md. Changes nothing.
# Queues a service has not declared yet (service not started) are reported as MISSING, not as a failure of the others.
# Exit 0 = every queue that exists is correct; 1 = a queue exists with wrong arguments; 2 = broker unreachable.
set -uo pipefail
CONTAINER="${RABBIT_CONTAINER:-thermogrid-rabbitmq}"
rq() { docker exec "$CONTAINER" rabbitmqctl -q "$@" 2>/dev/null; }

out="$(rq list_queues name durable arguments --formatter json)" || { echo "cannot reach $CONTAINER"; exit 2; }
bindings="$(rq list_bindings source_name destination_name routing_key --formatter json)" || exit 2

# name | kind | ttl_ms (or -) | max_length (or -)
EXPECTED=(
  "q.telemetry.rack-readings|lossless|-|50000"
  "q.telemetry.zone-readings|lossless|-|20000"
  "q.scheduler.workloads|lossless|-|50000"
  "q.simulator.workloads|lossless|-|50000"
  "q.cooling.zone-readings|state|30000|20000"
  "q.cooling.alerts|state|60000|20000"
  "q.scheduler.alerts|state|60000|50000"
  "q.scheduler.cooling|state|60000|50000"
  "q.simulator.cooling|state|60000|50000"
)
python - "$out" "$bindings" "${EXPECTED[@]}" <<'PY'
import json, sys
queues = {q["name"]: q for q in json.loads(sys.argv[1])}
binds = json.loads(sys.argv[2])
bad = 0
for line in sys.argv[3:]:
    name, kind, ttl, maxlen = line.split("|")
    q = queues.get(name)
    if q is None:
        print(f"MISSING  {name} (its service has not started)")
        continue
    a = {k: v for k, _t, v in q.get("arguments", [])}
    problems = []
    if not q.get("durable"): problems.append("not durable")
    if kind == "lossless":
        if "x-message-ttl" in a: problems.append("has a TTL")
        if a.get("x-dead-letter-exchange") != "thermogrid.dlx": problems.append("no dead-letter exchange")
    else:
        if str(a.get("x-message-ttl")) != ttl: problems.append(f"ttl {a.get('x-message-ttl')} != {ttl}")
        if "x-dead-letter-exchange" in a: problems.append("has a dead-letter exchange")
    if str(a.get("x-max-length")) != maxlen: problems.append(f"max-length {a.get('x-max-length')} != {maxlen}")
    src = [b for b in binds if b["destination_name"] == name and b["source_name"] == "thermogrid.events"]
    if not src: problems.append("not bound to thermogrid.events")
    print(("BAD      " if problems else "OK       ") + name + ("  " + "; ".join(problems) if problems else ""))
    bad += bool(problems)
dlq = [b for b in binds if b["destination_name"] == "thermogrid.dlq" and b["source_name"] == "thermogrid.dlx" and b["routing_key"] == "#"]
print("OK       thermogrid.dlq bound to thermogrid.dlx with #" if dlq else "MISSING  thermogrid.dlq binding (#)")
sys.exit(1 if bad else 0)
PY
