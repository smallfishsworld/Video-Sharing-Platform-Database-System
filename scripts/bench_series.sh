#!/usr/bin/env bash
# Interleaved benchmark runs of the 2023 implementation (B) and this version (O) on one boot:
#
#   scripts/bench_series.sh --variant plain|settle|analyze --prefix NAME [--rounds 3] [--only B|O]
#       [--round round2|round3] [--baseline DIR]
#
# Round i runs B then O through scripts/bench_instrumented.py, each run under its own exclusive
# flock on $BENCH_LOCK (default /home/user/work/bench.lock), released between runs. Results go to
# docs/benchmark/<round>/<variant>/ (round2 by default) as <prefix>-B<i> and <prefix>-O<i> (.json
# report, .log, .monitor.json). The series stops if the machine rebooted since its first run: such
# a series has to be redone.
#
# B is the checkout in --baseline (or $BASELINE; default /home/user/work/vs-baseline, a worktree
# of branch bench/baseline-clock) with its jar built in run/, O this checkout with its jar in run/.
# Each run DROPS AND RECREATES the public schema of database sustc on localhost. The harness is
# $BENCH_HARNESS (default scripts/run_bench.sh), which writes its raw files to $BENCH_OUT (default
# /home/user/work/bench6).
set -euo pipefail
usage() {
    sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
}
VARIANT=plain PREFIX="" ROUNDS=3 ONLY="" ROUND=round2 BASE=${BASELINE:-/home/user/work/vs-baseline}
while [ $# -gt 0 ]; do
    case "$1" in
        --variant) VARIANT=${2:?}; shift 2 ;;
        --prefix) PREFIX=${2:?}; shift 2 ;;
        --rounds) ROUNDS=${2:?}; shift 2 ;;
        --only) ONLY=${2:?}; shift 2 ;;
        --round) ROUND=${2:?}; shift 2 ;;
        --baseline) BASE=${2:?}; shift 2 ;;
        -h|--help) usage ;;
        *) echo "unknown argument: $1" >&2; usage ;;
    esac
done
[ -n "$PREFIX" ] || usage
case "$VARIANT" in plain) FLAG="" ;; settle) FLAG=--settle ;; analyze) FLAG=--analyze ;; *) usage ;; esac
REPO=$(cd "$(dirname "$0")/.." && pwd)
BASE=$(cd "$BASE" && pwd)
OUT="$REPO/docs/benchmark/$ROUND/$VARIANT"
Q="$REPO/docs/benchmark/round2/queries"
boot=$(uptime -s)
for i in $(seq "$ROUNDS"); do
    for v in B O; do
        [ -z "$ONLY" ] || [ "$ONLY" = "$v" ] || continue
        if [ "$(uptime -s)" != "$boot" ]; then
            echo "the machine rebooted during the series; redo it" >&2
            exit 1
        fi
        if [ "$v" = B ]; then
            python3 "$REPO/scripts/bench_instrumented.py" --repo "$BASE" --label "$PREFIX-B$i" --out "$OUT" $FLAG \
                --friend-sql "$Q/friends-baseline.sql" --friend-mid 18398 "$BASE/sql/build.sql" "$BASE/sql/audit.sql"
        else
            python3 "$REPO/scripts/bench_instrumented.py" --repo "$REPO" --label "$PREFIX-O$i" --out "$OUT" $FLAG \
                --friend-sql "$Q/friends-optimized.sql" --friend-mid 18398 "$REPO/sql/build.sql" "$REPO/sql/audit.sql"
        fi
    done
done
