-- How much EXPLAIN ANALYZE's per-node timing adds to a row-heavy plan on this machine.
-- Data: the public schema as left by a benchmark run (32 videos, 638,119 views).
-- Run (libpq variables as in scripts/benchmark.sh; hold the benchmark lock on a shared machine):
--   flock "$BENCH_LOCK" psql -X -f docs/experiments/explain-overhead.sql
-- The recorded output is docs/experiments/explain-overhead.txt.
-- Timer cost (/usr/lib/postgresql/16/bin/pg_test_timing -d 1): 27.33 ns per loop, clocksource tsc.
\set q 'select v.bv, count(r.mid) as views, sum(r.view_time) as total from videos v left join viewers r on v.bv = r.bv group by v.bv'
set jit = on;
:q \g /dev/null
\echo timing-on
explain (analyze, timing on) :q \g | grep 'Execution Time'
explain (analyze, timing on) :q \g | grep 'Execution Time'
explain (analyze, timing on) :q \g | grep 'Execution Time'
\echo timing-off
explain (analyze, timing off) :q \g | grep 'Execution Time'
explain (analyze, timing off) :q \g | grep 'Execution Time'
explain (analyze, timing off) :q \g | grep 'Execution Time'
\echo plain
\timing on
:q \g /dev/null
:q \g /dev/null
:q \g /dev/null
