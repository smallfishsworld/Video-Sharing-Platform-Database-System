#!/usr/bin/env bash
# Runs the course benchmark (sustc-runner, profile "benchmark") once against a freshly created
# schema and saves its report as <out>/<label>.json (plus the runner's log as <label>.log).
#
#   scripts/benchmark.sh --label optimized-r1 [--out docs/benchmark] [--no-build] [--allow-remote]
#
# The database comes from the usual libpq variables, with the defaults of sustc-runner/compose.yml:
#   PGHOST=localhost PGPORT=5432 PGDATABASE=sustc PGUSER=sustc PGPASSWORD=sustc
#
# WARNING: the public schema of that database is DROPPED and recreated from sql/build.sql and
# sql/audit.sql. For that reason only a loopback host is accepted unless --allow-remote is given.
#
# The runner settings are the ones used for every number in docs/benchmark: JVM time zone
# Asia/Shanghai and the service clock pinned to 2023-12-30 12:00 +08:00, when the bundled data
# was generated (several cases compare public times with "now"). Load on the database server is
# bounded by the application's connection pool (Hikari default: 10 connections) and the bulk
# importer's parallelism (sustc.import.parallelism, default 4 connections); there is no other
# network traffic. If BENCH_LOCK names a file, the run holds an exclusive flock on it so that
# timing runs on a shared machine do not overlap.
set -euo pipefail

usage() {
    sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

REPO=$(cd "$(dirname "$0")/.." && pwd)
LABEL=""
OUT="$REPO/docs/benchmark"
BUILD=1
ALLOW_REMOTE=0
while [ $# -gt 0 ]; do
    case "$1" in
        --label) LABEL=${2:?}; shift 2 ;;
        --out) OUT=${2:?}; shift 2 ;;
        --no-build) BUILD=0; shift ;;
        --allow-remote) ALLOW_REMOTE=1; shift ;;
        -h|--help) usage ;;
        *) echo "unknown argument: $1" >&2; usage ;;
    esac
done
[ -n "$LABEL" ] || usage
case "$LABEL" in *[!A-Za-z0-9._-]*) echo "the label may only contain letters, digits, '.', '_' and '-'" >&2; exit 2 ;; esac

export PGHOST=${PGHOST:-localhost} PGPORT=${PGPORT:-5432} PGDATABASE=${PGDATABASE:-sustc}
export PGUSER=${PGUSER:-sustc} PGPASSWORD=${PGPASSWORD:-sustc}

case "$PGHOST" in
    localhost|127.*|::1|"[::1]") ;;
    *)
        if [ "$ALLOW_REMOTE" -ne 1 ]; then
            echo "refusing to drop the schema of non-loopback host '$PGHOST'; pass --allow-remote if you mean it" >&2
            exit 2
        fi
        ;;
esac

DATA="$REPO/sustc-runner/data"
if ! ls "$DATA"/import/*.ser >/dev/null 2>&1 || ! ls "$DATA"/test/*.ser >/dev/null 2>&1; then
    echo "benchmark data missing: put the course's import/*.ser and test/*.ser files under $DATA" >&2
    exit 1
fi

if [ "$BUILD" -eq 1 ]; then
    (cd "$REPO" && ./gradlew --quiet :sustc-runner:bootJar)
fi
JAR="$REPO/run/sustc-runner.jar"
[ -f "$JAR" ] || { echo "$JAR not found; run without --no-build" >&2; exit 1; }
mkdir -p "$OUT"

run() {
    PGOPTIONS="-c client_min_messages=warning" psql -q -v ON_ERROR_STOP=1 -c "drop schema public cascade; create schema public;" >/dev/null
    for f in "$REPO/sql/build.sql" "$REPO/sql/audit.sql"; do
        psql -q -v ON_ERROR_STOP=1 -f "$f" >/dev/null
    done
    cd "$REPO/sustc-runner"
    rm -f benchmark-*.json
    SPRING_DATASOURCE_URL="jdbc:postgresql://$PGHOST:$PGPORT/$PGDATABASE" \
    SPRING_DATASOURCE_USERNAME="$PGUSER" SPRING_DATASOURCE_PASSWORD="$PGPASSWORD" \
        java -Duser.timezone=Asia/Shanghai -Dsustc.clock.start=2023-12-30T12:00:00+08:00 \
            -jar "$JAR" --spring.profiles.active=benchmark </dev/null >"$OUT/$LABEL.log" 2>&1
    cp benchmark-*.json "$OUT/$LABEL.json"
    rm -f benchmark-*.json
}

if [ -n "${BENCH_LOCK:-}" ]; then
    exec 9>>"$BENCH_LOCK"
    flock 9
fi
start=$(date +%s)
run
echo "wall=$(($(date +%s) - start))s" >>"$OUT/$LABEL.log"
echo "$OUT/$LABEL.json: $(cat "$OUT/$LABEL.json")"
