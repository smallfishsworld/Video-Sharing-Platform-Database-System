#!/bin/bash
# usage: run_bench.sh <repo_dir> <label> [schema files...]
# Runs the course benchmark against a freshly created schema. The service clock is pinned to
# 2023-12-30 12:00 (+08:00), when the bundled test data was generated.
#
# Vendored from /home/user/work/bench6/run_bench.sh, the harness that made the recorded runs
# (docs/benchmark/round{1,2,3}). One change: the log and the report go to $BENCH_OUT, by default
# /home/user/work/bench6 as before. The schema reset, the JVM flags and the database (sustc on
# localhost) are unchanged. It DROPS AND RECREATES the public schema of that database.
REPO=$1; LABEL=$2; shift 2
OUT=${BENCH_OUT:-/home/user/work/bench6}
mkdir -p "$OUT"
export PGPASSWORD=sustc
psql -q -h localhost -U sustc -d sustc -c "drop schema public cascade; create schema public;" >/dev/null 2>&1
for f in "$@"; do psql -q -h localhost -U sustc -d sustc -v ON_ERROR_STOP=1 -f "$f" >/dev/null 2>&1 || { echo "schema failed $f"; exit 1; }; done
cd "$REPO/sustc-runner"
rm -f benchmark-*.json
start=$(date +%s)
java -Duser.timezone=Asia/Shanghai -Dsustc.clock.start=2023-12-30T12:00:00+08:00 -jar "$REPO/run/sustc-runner.jar" --spring.profiles.active=benchmark < /dev/null > "$OUT/$LABEL.log" 2>&1
end=$(date +%s)
cp benchmark-*.json "$OUT/$LABEL.json" 2>/dev/null
echo "wall=$((end-start))s" >> "$OUT/$LABEL.log"
echo "done $LABEL: $(cat "$OUT/$LABEL.json")"
