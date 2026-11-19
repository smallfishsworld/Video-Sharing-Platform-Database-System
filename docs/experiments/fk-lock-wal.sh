#!/usr/bin/env bash
# What one write after a checkpoint puts into the WAL, by record type (pg_waldump --stats=record),
# on the benchmark data as this version imports it. For 200 danmus from 200 different users, sent
# right after a CHECKPOINT, the foreign key danmus.mid -> users locks each user's row
# (FOR KEY SHARE), which is a Heap/LOCK record and, for the first change of a page after the
# checkpoint, a full-page image of that users page. The same batch sent again, without a
# checkpoint in between, shows the same writes once those pages are already in the WAL.
#
#   BENCH_LOCK=/home/user/work/bench.lock docs/experiments/fk-lock-wal.sh > docs/experiments/fk-lock-wal.txt
#
# Needs the data imported by this version in the public schema, sudo -u postgres for CHECKPOINT
# and pg_waldump, and PostgreSQL 16 in /usr/lib/postgresql/16. The danmus are inserted in a
# transaction that is rolled back; only WAL is written.
set -euo pipefail
export PGHOST=${PGHOST:-localhost} PGPORT=${PGPORT:-5432} PGDATABASE=${PGDATABASE:-sustc}
export PGUSER=${PGUSER:-sustc} PGPASSWORD=${PGPASSWORD:-sustc}
case "$PGHOST" in localhost|127.*|::1) ;; *) echo "refusing non-loopback host '$PGHOST'" >&2; exit 2 ;; esac
if [ -n "${BENCH_LOCK:-}" ]; then
    exec 9>>"$BENCH_LOCK"
    flock 9
fi
WALDIR=$(sudo -n -u postgres psql -XAtqc "show data_directory")/pg_wal
q() { psql -XAtq -v ON_ERROR_STOP=1 -c "$1"; }
batch() {  # 200 danmus to one video by 200 users in a fixed pseudo-random order, rolled back
    q "begin;
       insert into danmus (bv, mid, time, content, post_time)
       select v.bv, u.mid, 1.0, 'wal test', timestamp '2023-12-30 12:00:00'
       from (select mid from users order by md5(mid::text) limit 200) u,
            (select bv from videos order by bv limit 1) v;
       rollback;" >/dev/null
}
stats() {  # stats <from> <to>: the record types that wrote anything, and the total
    sudo -n -u postgres /usr/lib/postgresql/16/bin/pg_waldump --stats=record -s "$1" -e "$2" -p "$WALDIR" 2>&1 \
        | awk 'NR <= 2 || /^Total/ || /error/ || ($2 + 0 > 0 && /\//)' || true
}
echo "# docs/experiments/fk-lock-wal.sh, $(date -u +%FT%TZ), boot $(uptime -s)"
q "select version()"
q "select string_agg(name || '=' || setting, ' ') from pg_settings where name in ('full_page_writes', 'wal_compression', 'data_checksums', 'wal_log_hints')"
echo "users: $(q "select count(*) from users") rows in $(q "select relpages from pg_class where relname = 'users'") pages"
sudo -n -u postgres psql -XAtqc checkpoint
a=$(q "select pg_current_wal_insert_lsn()")
batch
b=$(q "select pg_current_wal_insert_lsn()")
batch
c=$(q "select pg_current_wal_insert_lsn()")
# pg_waldump reads the WAL files: wait until the server has flushed them past the second batch
# (the batches are rolled back, and an abort does not flush; the WAL writer does within ~200 ms).
for _ in $(seq 100); do
    [ "$(q "select pg_current_wal_flush_lsn() >= '$c'::pg_lsn")" = t ] && break
    sleep 0.1
done
echo
echo "## 200 danmus by 200 users, right after a CHECKPOINT ($a .. $b)"
stats "$a" "$b"
echo
echo "## the same 200 danmus again, right after the first batch ($b .. $c)"
stats "$b" "$c"
