#!/bin/sh
# Runs only during initdb of an EMPTY, explicitly selected PostgreSQL volume.
set -eu
for role in "$POSTGRES_USER" "$DB_MIGRATION_USERNAME" "$DB_RUNTIME_USERNAME"; do
  printf '%s' "$role" | grep -Eq '^[a-z][a-z0-9_]{0,62}$' || { echo 'Invalid database role name.' >&2; exit 1; }
done
[ "$POSTGRES_USER" != "$DB_MIGRATION_USERNAME" ] && [ "$POSTGRES_USER" != "$DB_RUNTIME_USERNAME" ] && [ "$DB_MIGRATION_USERNAME" != "$DB_RUNTIME_USERNAME" ] || { echo 'Database identities must be distinct.' >&2; exit 1; }
[ -n "$DB_MIGRATION_PASSWORD" ] && [ -n "$DB_RUNTIME_PASSWORD" ] || { echo 'Database credentials are required.' >&2; exit 1; }
psql -X -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv migration_user DB_MIGRATION_USERNAME
\getenv migration_password DB_MIGRATION_PASSWORD
\getenv runtime_user DB_RUNTIME_USERNAME
\getenv runtime_password DB_RUNTIME_PASSWORD
\getenv database_name POSTGRES_DB
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD %L', :'migration_user', :'migration_password') \gexec
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD %L', :'runtime_user', :'runtime_password') \gexec
ALTER DATABASE :"database_name" OWNER TO :"migration_user";
REVOKE ALL ON DATABASE :"database_name" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"database_name" TO :"runtime_user";
ALTER SCHEMA public OWNER TO :"migration_user";
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO :"runtime_user";
SQL
