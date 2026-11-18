#!/bin/bash
# ThermoGrid: one MySQL user per service, granted privileges on its own schema only.
# Passwords come from environment variables passed in by docker-compose.yml.
# Sourced by the mysql image entrypoint on first initialisation only.
set -eu
export MYSQL_PWD="${MYSQL_ROOT_PASSWORD}"   # keeps the password off the command line

esc() { local s=${1//\\/\\\\}; printf '%s' "${s//\'/\'\'}"; }   # escape \ and ' for SQL literals

# user:schema:ENV_VAR_HOLDING_PASSWORD
for spec in \
  auth:auth_db:AUTH_DB_PASSWORD \
  topology:topology_db:TOPOLOGY_DB_PASSWORD \
  telemetry:telemetry_db:TELEMETRY_DB_PASSWORD \
  workload:workload_db:WORKLOAD_DB_PASSWORD \
  scheduler:scheduler_db:SCHEDULER_DB_PASSWORD \
  cooling:cooling_db:COOLING_DB_PASSWORD; do
  IFS=: read -r user schema var <<< "$spec"
  pw="${!var:-}"
  if [ -z "$pw" ]; then echo "02-users.sh: $var is not set" >&2; exit 1; fi
  mysql -uroot <<SQL
CREATE USER IF NOT EXISTS '${user}'@'%' IDENTIFIED BY '$(esc "$pw")';
GRANT ALL PRIVILEGES ON \`${schema}\`.* TO '${user}'@'%';
SQL
done
mysql -uroot -e "FLUSH PRIVILEGES;"