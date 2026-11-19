#!/usr/bin/env bash
# Runs WriteBench.java against the service classes of one runner jar.
#
#   scripts/writebench/run.sh --jar run/sustc-runner.jar --sql "sql/build.sql sql/audit.sql" \
#       --out result.json [--code-sha SHA] -- [WriteBench options: --mode, --clients, --ops, ...]
#
# Before the run it DROPS AND RECREATES the public schema of the database (default sustc on
# localhost) and applies the given SQL files, like the benchmark script does. WriteBench then
# imports the benchmark data through the jar's DatabaseService. Only loopback hosts are accepted;
# WriteBench caps the load at 16 client threads and 5,000 calls per operation.
#
# Wrap timing runs in flock, e.g. flock /home/user/work/bench.lock scripts/writebench/run.sh ...
# --code-sha is a label of the caller's choice; the JSON also records, taken inside the lock just
# before the run, git HEAD and git status --porcelain of this checkout and, for every --sql file,
# its SHA-256 and the HEAD of the repository that holds it (with "+modified" if it differs from
# that HEAD).
set -euo pipefail

usage() {
    sed -n '2,16p' "$0" >&2
    exit 2
}

jar="" sql="" out="" code_sha="" host=localhost db=sustc user=sustc
while [[ $# -gt 0 ]]; do
    case "$1" in
        --jar) jar=$2; shift 2 ;;
        --sql) sql=$2; shift 2 ;;
        --out) out=$2; shift 2 ;;
        --code-sha) code_sha=$2; shift 2 ;;
        --host) host=$2; shift 2 ;;
        --db) db=$2; shift 2 ;;
        --) shift; break ;;
        -h|--help) usage ;;
        *) echo "unknown argument: $1" >&2; usage ;;
    esac
done
[[ -n $jar && -n $sql && -n $out ]] || usage
case "$host" in
    localhost|127.0.0.1|::1) ;;
    *) echo "refusing non-loopback host $host" >&2; exit 2 ;;
esac

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
jar=$(realpath "$jar")
out=$(realpath -m "$out")
mkdir -p "$(dirname "$out")"
jar_sha=$(sha256sum "$jar" | cut -d' ' -f1)
cache=${WRITEBENCH_CACHE:-${TMPDIR:-/tmp}/writebench}/$jar_sha
if [[ ! -d $cache/lib ]]; then
    rm -rf "$cache"
    mkdir -p "$cache/x"
    (cd "$cache/x" && unzip -q "$jar" 'BOOT-INF/lib/*')
    mv "$cache/x/BOOT-INF/lib" "$cache/lib"
    rm -rf "$cache/x"
fi
classes=$cache/classes
if [[ ! -f $classes/WriteBench.class || $here/WriteBench.java -nt $classes/WriteBench.class ]]; then
    mkdir -p "$classes"
    javac -nowarn --release 17 -cp "$cache/lib/*" -d "$classes" "$here/WriteBench.java"
fi
cat > "$cache/logback.xml" <<'EOF'
<configuration>
  <appender name="out" class="ch.qos.logback.core.ConsoleAppender">
    <encoder><pattern>%d{HH:mm:ss.SSS} %-5level %logger{20} - %msg%n</pattern></encoder>
  </appender>
  <!-- The runner's application.yml: root off, io.sustc at debug. -->
  <root level="OFF"><appender-ref ref="out"/></root>
  <logger name="io.sustc" level="DEBUG"/>
</configuration>
EOF

# Provenance of what this run measures, recorded at the start of the run (inside the caller's lock).
# GIT_OPTIONAL_LOCKS=0 keeps git status from refreshing the index of the repositories it reads.
export GIT_OPTIONAL_LOCKS=0
git_head=$(git -C "$repo" rev-parse HEAD 2>/dev/null || echo unknown)
git_porcelain=$(git -C "$repo" status --porcelain 2>/dev/null || true)
git_porcelain_lines=$(printf '%s' "$git_porcelain" | grep -c . || true)
sql_sha256="" sql_git=""
for f in $sql; do
    sql_sha256+="${sql_sha256:+ }$f=$(sha256sum "$f" | cut -d' ' -f1)"
    d=$(dirname "$f")
    if head=$(git -C "$d" rev-parse HEAD 2>/dev/null); then
        [[ -n $(git -C "$d" status --porcelain -- "$(basename "$f")") ]] && head+="+modified"
    else
        head=none
    fi
    sql_git+="${sql_git:+ }$f=$head"
done

export PGPASSWORD=${PGPASSWORD:-sustc} PGOPTIONS="-c client_min_messages=warning"
psql -q -h "$host" -U "$user" -d "$db" -c "drop schema public cascade; create schema public;" >/dev/null
for f in $sql; do
    psql -q -h "$host" -U "$user" -d "$db" -v ON_ERROR_STOP=1 -f "$f" >/dev/null
done

log=${out%.json}.log
java -Duser.timezone=Asia/Shanghai -Dsustc.clock.start=2023-12-30T12:00:00+08:00 \
    -Dlogback.configurationFile="$cache/logback.xml" \
    -cp "$classes:$cache/lib/*" WriteBench \
    --url "jdbc:postgresql://$host:5432/$db" --data "$repo/sustc-runner/data" --out "$out.tmp" \
    --jar "$jar" --jar_sha256 "$jar_sha" --code_sha "${code_sha:-unknown}" --sql "$sql" \
    --sql_sha256 "$sql_sha256" --sql_git "$sql_git" --git_head "$git_head" \
    --git_porcelain_lines "$git_porcelain_lines" --git_porcelain "$(printf '%s' "$git_porcelain" | tr '\n' ';')" \
    --boot "$(uptime -s)" --java "$(java -version 2>&1 | grep -v "^Picked up" | head -1)" \
    --server "$(psql -Atq -h "$host" -U "$user" -d "$db" -c 'select version()')" \
    "$@" > "$log" 2>&1
mv "$out.tmp" "$out"
echo "wrote $out"
