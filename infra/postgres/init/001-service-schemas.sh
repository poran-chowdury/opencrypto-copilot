#!/bin/sh
# Database-per-service inside one Postgres instance: each service gets its own schema and login,
# and can only touch its own schema. Tables are created by each service's Flyway migrations.
# Runs once, on first start of an empty data volume (docker compose down -v to re-run).
set -e
for svc in asset wallet swap txn; do
  user="${svc}_svc"
  pass_var="$(echo "${svc}" | tr '[:lower:]' '[:upper:]')_DB_PASSWORD"
  pass="$(eval echo "\${${pass_var}:-${svc}_localdev}")"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
CREATE ROLE ${user} LOGIN PASSWORD '${pass}';
CREATE SCHEMA ${svc} AUTHORIZATION ${user};
REVOKE ALL ON SCHEMA public FROM ${user};
ALTER ROLE ${user} SET search_path = ${svc};
SQL
done
