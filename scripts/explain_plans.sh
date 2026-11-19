#!/usr/bin/env bash
# Regenerates the EXPLAIN (ANALYZE, BUFFERS) outputs in docs/explain from docs/explain/explain.sql.
#
#   scripts/explain_plans.sh [--original-from SCHEMA] [--no-import] [--allow-remote]
#
# Needs two schemas in the database (libpq variables as in scripts/benchmark.sh):
#   original  the schema of the original implementation (sql/build.sql of commit 2e811bd) with the
#             benchmark data. --original-from SCHEMA (re)creates it from that file and copies the
#             rows of every table from SCHEMA, which must have the original column layout, for
#             example the public schema after running the original implementation's import.
#             It also creates original_nostats: followings and users built the same way but never
#             analyzed (autovacuum off), like right after the original import.
#   public    the current schema; unless --no-import is given it is recreated here from
#             sql/build.sql + sql/audit.sql and loaded with the current importer ("db import").
# The public schema is DROPPED, so only a loopback host is accepted unless --allow-remote is given.
# If BENCH_LOCK names a file, the whole run holds an exclusive flock on it.
set -euo pipefail

REPO=$(cd "$(dirname "$0")/.." && pwd)
IMPORT=1
ALLOW_REMOTE=0
ORIGINAL_FROM=""
ORIGINAL_COMMIT=2e811bd
ORIGINAL_TABLES="users videos followings videolikes coins favorites viewers danmus danmulikes"
while [ $# -gt 0 ]; do
    case "$1" in
        --original-from) ORIGINAL_FROM=${2:?}; shift 2 ;;
        --no-import) IMPORT=0; shift ;;
        --allow-remote) ALLOW_REMOTE=1; shift ;;
        *) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
    esac
done
case "$ORIGINAL_FROM" in *[!A-Za-z0-9_]*) echo "invalid schema name '$ORIGINAL_FROM'" >&2; exit 2 ;; esac
export PGHOST=${PGHOST:-localhost} PGPORT=${PGPORT:-5432} PGDATABASE=${PGDATABASE:-sustc}
export PGUSER=${PGUSER:-sustc} PGPASSWORD=${PGPASSWORD:-sustc}
case "$PGHOST" in
    localhost|127.*|::1|"[::1]") ;;
    *) [ "$ALLOW_REMOTE" -eq 1 ] || { echo "refusing non-loopback host '$PGHOST' without --allow-remote" >&2; exit 2; } ;;
esac
export PGOPTIONS="-c client_min_messages=warning"

if [ -n "${BENCH_LOCK:-}" ]; then
    exec 9>>"$BENCH_LOCK"
    flock 9
fi
if [ -n "$ORIGINAL_FROM" ]; then
    {
        echo "drop schema if exists original cascade; create schema original; set search_path = original;"
        git -C "$REPO" show "$ORIGINAL_COMMIT:sql/build.sql"
        # (the file does not end with a newline)
        echo
        for t in $ORIGINAL_TABLES; do
            echo "insert into original.$t select * from $ORIGINAL_FROM.$t;"
        done
        echo "vacuum (analyze) $(echo $ORIGINAL_TABLES | sed 's/\([a-z]*\)/original.\1/g; s/ /, /g');"
        # followings and users as the original import leaves them: indexed, never analyzed
        echo "drop schema if exists original_nostats cascade; create schema original_nostats;"
        echo "set search_path = original_nostats;"
        git -C "$REPO" show "$ORIGINAL_COMMIT:sql/build.sql" | sed -n '/^drop table if exists users;/,/^create index followings_i2/p'
        echo
        for t in users followings; do
            echo "alter table original_nostats.$t set (autovacuum_enabled = off);"
            echo "insert into original_nostats.$t select * from $ORIGINAL_FROM.$t;"
        done
    } | psql -Xq -v ON_ERROR_STOP=1 >/dev/null
fi
[ "$(psql -XAtc "select count(*) from pg_namespace where nspname = 'original'")" = 1 ] \
    || { echo "schema original is missing; create it with --original-from" >&2; exit 1; }
if [ "$IMPORT" -eq 1 ]; then
    psql -Xq -v ON_ERROR_STOP=1 -c "drop schema public cascade; create schema public;" >/dev/null
    for f in "$REPO/sql/build.sql" "$REPO/sql/audit.sql"; do psql -Xq -v ON_ERROR_STOP=1 -f "$f" >/dev/null; done
    (cd "$REPO/sustc-runner" && SPRING_DATASOURCE_URL="jdbc:postgresql://$PGHOST:$PGPORT/$PGDATABASE" \
        SPRING_DATASOURCE_USERNAME="$PGUSER" SPRING_DATASOURCE_PASSWORD="$PGPASSWORD" \
        java -Duser.timezone=Asia/Shanghai -jar "$REPO/run/sustc-runner.jar" db import </dev/null \
        >"$REPO/docs/explain/import.log" 2>&1)
    grep -o "Import phase.*\|Imported .* in [0-9]* ms" "$REPO/docs/explain/import.log" || true
    # Let the checkpoint and the writeback that the import started calm down before timing.
    sleep 60
fi
cd "$REPO"
psql -X -f docs/explain/explain.sql >/dev/null
grep -H "Execution Time" docs/explain/*.txt
