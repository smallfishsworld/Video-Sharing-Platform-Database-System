#!/usr/bin/env bash
# Measures where the time of one write goes, for docs/ENGINEERING_NOTES.md ("Write path"), and
# writes the raw pgbench output to docs/experiments/write-latency.txt:
#
#   1. likeVideo / displayDanmu latency right after a fresh import, and again after the import's
#      dirty pages have been written back (SETTLE seconds later, default 120);
#   2. likeVideo with and without the derived-table triggers and the foreign keys;
#   3. the original implementation's likeVideo statement sequence on schema "original",
#      alternating with the current one.
#
#   scripts/write_latency.sh [--allow-remote]
#
# One client, prepared statements, 1000 transactions per measurement. The public schema is
# DROPPED and reloaded (see scripts/benchmark.sh for the connection variables and the loopback
# guard); it needs the runner jar (./gradlew :sustc-runner:bootJar) and, for part 3, the schema
# original described in scripts/explain_plans.sh (its likes are toggled, nothing else changes). If BENCH_LOCK names a file, the whole run holds
# an exclusive flock on it.
set -euo pipefail

REPO=$(cd "$(dirname "$0")/.." && pwd)
PGB="$REPO/scripts/pgbench"
OUT="$REPO/docs/experiments/write-latency.txt"
SETTLE=${SETTLE:-120}
TX=1000
[ "${1:-}" = "--allow-remote" ] && ALLOW_REMOTE=1 || ALLOW_REMOTE=0
export PGHOST=${PGHOST:-localhost} PGPORT=${PGPORT:-5432} PGDATABASE=${PGDATABASE:-sustc}
export PGUSER=${PGUSER:-sustc} PGPASSWORD=${PGPASSWORD:-sustc}
case "$PGHOST" in
    localhost|127.*|::1|"[::1]") ;;
    *) [ "$ALLOW_REMOTE" -eq 1 ] || { echo "refusing non-loopback host '$PGHOST' without --allow-remote" >&2; exit 2; } ;;
esac

if [ -n "${BENCH_LOCK:-}" ]; then
    exec 9>>"$BENCH_LOCK"
    flock 9
fi

export PGOPTIONS="-c client_min_messages=warning"
q() { psql -XAtq -v ON_ERROR_STOP=1 -c "$1"; }
dirty() { grep -E '^(Dirty|Writeback):' /proc/meminfo | tr -s ' ' | tr '\n' ' '; echo; }
# usage: bench <label> <schema> <script>
bench() {
    local videos
    videos=$(PGOPTIONS="$PGOPTIONS -c search_path=$2" q "select count(*) from bench_videos")
    printf '%-44s ' "$1"
    PGOPTIONS="$PGOPTIONS -c search_path=$2" pgbench -n -M prepared -c 1 -t "$TX" -D videos="$videos" -f "$PGB/$3" 2>&1 \
        | grep -E '^latency average' | tr '\n' ' '
    echo
}
helpers() {
    PGOPTIONS="$PGOPTIONS -c search_path=$1" psql -Xq -v ON_ERROR_STOP=1 -f "$PGB/helpers.sql" -f "$PGB/$2" >/dev/null
}

mkdir -p "$(dirname "$OUT")"
{
    echo "# scripts/write_latency.sh, $(date -u +%FT%TZ), $(nproc) CPUs, $TX transactions per line, 1 client"
    q "select version()"
    q "select string_agg(name || '=' || setting, ' ') from pg_settings where name in ('synchronous_commit', 'fsync', 'full_page_writes', 'wal_compression', 'checkpoint_timeout', 'max_wal_size', 'shared_buffers')"

    echo
    echo "## 1. Fresh import into public, then the same statements again after ${SETTLE}s"
    q "drop schema public cascade; create schema public;" >/dev/null
    for f in "$REPO/sql/build.sql" "$REPO/sql/audit.sql"; do psql -Xq -v ON_ERROR_STOP=1 -f "$f" >/dev/null; done
    (cd "$REPO/sustc-runner" && SPRING_DATASOURCE_URL="jdbc:postgresql://$PGHOST:$PGPORT/$PGDATABASE" \
        SPRING_DATASOURCE_USERNAME="$PGUSER" SPRING_DATASOURCE_PASSWORD="$PGPASSWORD" \
        java -Duser.timezone=Asia/Shanghai -jar "$REPO/run/sustc-runner.jar" db import </dev/null 2>&1 \
        | grep -o "Imported .* in [0-9]* ms")
    helpers public helpers_videos_current.sql
    echo "after import:   $(dirty)"
    bench "likeVideo, current, right after import" public like_current.pgb
    bench "displayDanmu, current, right after import" public display_current.pgb
    sleep "$SETTLE"
    echo "after ${SETTLE}s: $(dirty)"
    bench "likeVideo, current, settled" public like_current.pgb
    bench "displayDanmu, current, settled" public display_current.pgb

    echo
    echo "## 2. likeVideo without the derived-table triggers, then also without the foreign keys"
    q "alter table videolikes disable trigger derived_videolikes_insert; alter table videolikes disable trigger derived_videolikes_delete"
    bench "likeVideo, current, no triggers" public like_current.pgb
    q "alter table videolikes drop constraint videolikes_mid_fkey; alter table videolikes drop constraint videolikes_bv_fkey"
    bench "likeVideo, current, no triggers, no FKs" public like_current.pgb
    q "alter table videolikes add constraint videolikes_mid_fkey foreign key (mid) references users (mid) on delete cascade"
    q "alter table videolikes add constraint videolikes_bv_fkey foreign key (bv) references videos (bv) on delete cascade"
    q "alter table videolikes enable trigger derived_videolikes_insert; alter table videolikes enable trigger derived_videolikes_delete"
    bench "likeVideo, current, with both again" public like_current.pgb

    echo
    echo "## 3. Original vs current likeVideo statement sequence, alternating (settled data)"
    echo "# Both schemas pay full-page writes after each checkpoint start; alternating short rounds"
    echo "# exposes them to the same checkpoint phase."
    if [ "$(q "select count(*) from pg_namespace where nspname = 'original'")" = 1 ]; then
        helpers original helpers_videos_original.sql
        for round in 1 2 3; do
            bench "likeVideo, original, round $round" original like_original.pgb
            bench "likeVideo, current, round $round" public like_current.pgb
        done
        PGOPTIONS="$PGOPTIONS -c search_path=original" q "drop table bench_users, bench_videos"
    else
        echo "schema original missing; skipped"
    fi
} | tee "$OUT"
