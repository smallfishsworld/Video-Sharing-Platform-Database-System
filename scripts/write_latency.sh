#!/usr/bin/env bash
# Measures where the time of one write goes, for docs/ENGINEERING_NOTES.md ("Write path"), and
# writes the raw pgbench output to docs/experiments/write-latency.txt:
#
#   1. likeVideo / displayDanmu latency right after a fresh import, and again after the import's
#      dirty pages have been written back (SETTLE seconds later, default 120);
#   2. likeVideo with the derived-table triggers and foreign keys, without the triggers, and
#      without both, in alternating rounds;
#   3. the original implementation's likeVideo statement sequence on schema "original",
#      alternating with the current one.
#
#   scripts/write_latency.sh [--rounds N] [--allow-remote]
#
# One client, prepared statements, 1000 transactions per measurement, N rounds (default 5) in
# parts 2 and 3, with the median and range per configuration at the end. The public schema is
# DROPPED and reloaded (see scripts/benchmark.sh for the connection variables and the loopback
# guard); it needs the runner jar (./gradlew :sustc-runner:bootJar) and, for part 3, the schema
# original described in scripts/explain_plans.sh (its likes are toggled, nothing else changes).
# Part 2 recomputes video_stats.likes afterwards, and the triggers and foreign keys are restored
# on any exit. If BENCH_LOCK names a file, the whole run holds an exclusive flock on it.
set -euo pipefail
usage() {
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
}
REPO=$(cd "$(dirname "$0")/.." && pwd)
PGB="$REPO/scripts/pgbench"
OUT="$REPO/docs/experiments/write-latency.txt"
SETTLE=${SETTLE:-120}
TX=1000
ROUNDS=5
ALLOW_REMOTE=0
while [ $# -gt 0 ]; do
    case "$1" in
        --rounds) ROUNDS=${2:?}; shift 2 ;;
        --allow-remote) ALLOW_REMOTE=1; shift ;;
        -h|--help) usage ;;
        *) echo "unknown argument: $1" >&2; usage ;;
    esac
done
case "$ROUNDS" in ''|*[!0-9]*) echo "--rounds takes a number" >&2; exit 2 ;; esac
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

# Part 2 changes the schema; put it back however the script ends.
restore() {
    q "alter table if exists videolikes enable trigger derived_videolikes_insert" 2>/dev/null || true
    q "alter table if exists videolikes enable trigger derived_videolikes_delete" 2>/dev/null || true
    if [ "$(q "select count(*) from pg_constraint where conname = 'videolikes_mid_fkey'" 2>/dev/null)" = 0 ]; then
        q "alter table videolikes add constraint videolikes_mid_fkey foreign key (mid) references users (mid) on delete cascade" || true
    fi
    if [ "$(q "select count(*) from pg_constraint where conname = 'videolikes_bv_fkey'" 2>/dev/null)" = 0 ]; then
        q "alter table videolikes add constraint videolikes_bv_fkey foreign key (bv) references videos (bv) on delete cascade" || true
    fi
    q "drop table if exists public.bench_users, public.bench_videos" 2>/dev/null || true
    rm -f "$TMP"
}
TMP=$(mktemp "${OUT}.XXXXXX")
trap restore EXIT

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
no_triggers() {
    q "alter table videolikes disable trigger derived_videolikes_insert; alter table videolikes disable trigger derived_videolikes_delete"
}
with_triggers() {
    q "alter table videolikes enable trigger derived_videolikes_insert; alter table videolikes enable trigger derived_videolikes_delete"
}
no_fks() {
    q "alter table videolikes drop constraint videolikes_mid_fkey; alter table videolikes drop constraint videolikes_bv_fkey"
}
with_fks() {
    q "alter table videolikes add constraint videolikes_mid_fkey foreign key (mid) references users (mid) on delete cascade"
    q "alter table videolikes add constraint videolikes_bv_fkey foreign key (bv) references videos (bv) on delete cascade"
}

mkdir -p "$(dirname "$OUT")"
{
    echo "# scripts/write_latency.sh, $(date -u +%FT%TZ), $(nproc) CPUs, $TX transactions per line, 1 client, $ROUNDS rounds"
    echo "# commit $(git -C "$REPO" rev-parse HEAD) ($(git -C "$REPO" status --porcelain | wc -l) uncommitted paths), boot $(uptime -s)"
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
    echo "## 2. likeVideo with triggers and foreign keys, without the triggers, without both; alternating rounds"
    for round in $(seq "$ROUNDS"); do
        bench "likeVideo, triggers+FKs, round $round" public like_current.pgb
        no_triggers
        bench "likeVideo, no triggers, round $round" public like_current.pgb
        no_fks
        bench "likeVideo, no triggers no FKs, round $round" public like_current.pgb
        with_fks
        with_triggers
    done
    # The toggles without triggers left video_stats.likes behind; recompute it.
    q "update video_stats s set likes = (select count(*) from videolikes l where l.bv = s.bv)" >/dev/null
    echo
    echo "## 3. Original vs current likeVideo statement sequence, alternating (settled data)"
    if [ "$(q "select count(*) from pg_namespace where nspname = 'original'")" = 1 ]; then
        helpers original helpers_videos_original.sql
        for round in $(seq "$ROUNDS"); do
            bench "likeVideo, original, round $round" original like_original.pgb
            bench "likeVideo, current, round $round" public like_current.pgb
        done
        PGOPTIONS="$PGOPTIONS -c search_path=original" q "drop table bench_users, bench_videos"
    else
        echo "schema original missing; skipped"
    fi
} > "$TMP"
{
    cat "$TMP"
    echo
    echo "## Summary: median [min-max] of the rounds, ms"
    python3 - "$TMP" <<'EOF'
import re, statistics, sys
groups = {}
for line in open(sys.argv[1]):
    m = re.match(r"(.+?), round \d+\s+latency average = ([\d.]+) ms", line)
    if m:
        groups.setdefault(m.group(1).strip(), []).append(float(m.group(2)))
for k, v in groups.items():
    print(f"{k:<40} {statistics.median(v):.3f} [{min(v):.3f}-{max(v):.3f}] (n={len(v)})")
EOF
} > "$OUT.new"
mv "$OUT.new" "$OUT"
cat "$OUT"
