#!/usr/bin/env bash
# Verifies the start order of scripts/start-all.sh without starting anything (DRY_RUN=1), and that the script
# contains no hard-coded secrets. Exit code 0 = pass.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$(DRY_RUN=1 "$ROOT/scripts/start-all.sh" 2>&1)" || { echo "FAIL: dry run exited non-zero"; echo "$out"; exit 1; }

expected=(infrastructure eureka-server auth-service topology-service telemetry-service workload-service scheduler-service cooling-controller-service simulator-service api-gateway)
prev=-1; fail=0
for item in "${expected[@]}"; do
  line="$(printf '%s\n' "$out" | grep -n -E "^\[start-all\] [0-9]+/9 .*${item}" | head -1 | cut -d: -f1)"
  if [ -z "$line" ]; then echo "FAIL: '$item' missing from the plan"; fail=1; continue; fi
  if [ "$line" -le "$prev" ]; then echo "FAIL: '$item' is out of order"; fail=1; fi
  prev="$line"
done

# consumers before the producers of their events (plan section 13)
pos() { printf '%s\n' "$out" | grep -n -E "^\[start-all\] [0-9]+/9 $1" | head -1 | cut -d: -f1; }
[ "$(pos telemetry-service)" -lt "$(pos simulator-service)" ] || { echo "FAIL: telemetry must start before the simulator"; fail=1; }
[ "$(pos scheduler-service)" -lt "$(pos simulator-service)" ] || { echo "FAIL: scheduler must start before the simulator"; fail=1; }
[ "$(pos cooling-controller-service)" -lt "$(pos simulator-service)" ] || { echo "FAIL: cooling controller must start before the simulator"; fail=1; }
printf '%s\n' "$out" | grep -q "Eureka status UP for SCHEDULER-SERVICE" || { echo "FAIL: no Eureka readiness check for the scheduler"; fail=1; }

if grep -n -i -E "password=|secret=|token=" "$ROOT/scripts/start-all.sh" | grep -v -E "^\s*[0-9]+:\s*#" ; then
  echo "FAIL: possible hard-coded secret in start-all.sh"; fail=1
fi
[ -x "$ROOT/scripts/start-all.sh" ] || { echo "FAIL: start-all.sh is not executable"; fail=1; }

if [ "$fail" -eq 0 ]; then echo "PASS: start order and readiness checks are as specified"; else exit 1; fi
