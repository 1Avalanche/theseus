#!/usr/bin/env bash
# Базы и роли стендов (DECISIONS.md §12, docs/database.md).
# Для каждого стенда: база theseus_<стенд>, роли theseus_<стенд>_core / _agent со своей схемой,
# без доступа к базе другого стенда. Идемпотентен: Postgres запускает его при первой инициализации
# тома, повторно — вручную:
#   docker exec theseus-postgres bash /docker-entrypoint-initdb.d/10-theseus.sh
set -euo pipefail

psql_admin() {
    psql -v ON_ERROR_STOP=1 --username postgres --no-password "$@"
}

for stand in prod test; do
    upper=$(echo "$stand" | tr '[:lower:]' '[:upper:]')
    db="theseus_${stand}"
    core_role="theseus_${stand}_core"
    agent_role="theseus_${stand}_agent"
    core_password_var="THESEUS_${upper}_CORE_DB_PASSWORD"
    agent_password_var="THESEUS_${upper}_AGENT_DB_PASSWORD"

    psql_admin --dbname postgres \
        -v db="$db" -v core_role="$core_role" -v agent_role="$agent_role" \
        -v core_pw="${!core_password_var}" -v agent_pw="${!agent_password_var}" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'core_role', :'core_pw')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'core_role') \gexec
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'agent_role', :'agent_pw')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'agent_role') \gexec
SELECT format('ALTER ROLE %I PASSWORD %L', :'core_role', :'core_pw') \gexec
SELECT format('ALTER ROLE %I PASSWORD %L', :'agent_role', :'agent_pw') \gexec
SELECT format('CREATE DATABASE %I', :'db')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db') \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'db') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I, %I', :'db', :'core_role', :'agent_role') \gexec
SQL

    psql_admin --dbname "$db" -v core_role="$core_role" -v agent_role="$agent_role" <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('CREATE SCHEMA IF NOT EXISTS core AUTHORIZATION %I', :'core_role') \gexec
SELECT format('CREATE SCHEMA IF NOT EXISTS agent AUTHORIZATION %I', :'agent_role') \gexec
SQL
    echo "theseus: стенд ${stand} готов (${db})"
done
