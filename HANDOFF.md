# Handoff

The state of the `optimize` branch, how to work with it, and where to continue. The overview is in [README.md](README.md), and the reasoning and measurements are in [docs/ENGINEERING_NOTES.md](docs/ENGINEERING_NOTES.md).

## State

The base is `2e811bd`, the original course implementation with the build fixed. The branch adds:

| Commit | What |
|---|---|
| `66edb47` | Service layer rewritten for correctness (single-statement writes, one visibility rule, foreign keys with cascades); `COPY`-based bulk importer |
| `95a7e83` | `video_stats` and `video_coviews` maintained by statement-level triggers; recommendation, search and view-rate queries read them |
| `bf9936d` | 103 JUnit 5 + Testcontainers tests for every service and the importer; a fix for foreign keys left `NOT VALID` after a failed import |
| `10339a3` | GitHub Actions CI (JDK 17 and 21); the benchmark, EXPLAIN and write-latency scripts; recorded plans and experiments |
| `13b980d` | the two READMEs, the engineering notes, this file, the raw benchmark reports in `docs/benchmark`; `sql/` declared as a test input |
| `978fe40` | executable bits on `gradlew` and the scripts; CI runs the wrapper through `bash` |
| `94d8ed7` … `050cf28` | concurrency: deadlock retries and savepoints inside a caller's transaction, a per-user lock for toggles, the review state an edit replaced, co-view maintenance one transaction per user; `ConcurrencyTest` |
| `89e39e2` … `cad2e6e` | ICU case folding and byte-order tie-breaks, no credentials in the audit log, exact re-creation of schema objects by the importer, tests for documented behaviours |
| `1f1225b` … the last commit | benchmark tooling (WriteBench, the instrumented and interleaved series, the WAL and EXPLAIN experiments), round-1 raw files moved to `docs/benchmark/round1`, documentation corrected against the round-2 measurements |

The commits up to `13b980d` mix concerns (for example `66edb47`); they were not rewritten, because the round-1 raw files and the review refer to them by hash. The fix round from `978fe40` on has one concern per commit.

Where it stands:
- **Benchmark.** All 5,622 cases pass in every run. In the interleaved round-2 runs (medians of 3, [docs/benchmark/round2/plain](docs/benchmark/round2/plain)) the 21 timed steps take 4,433 ms against 21,903 ms for the original: read steps 1,080 against 19,057 ms, write steps 3,353 against 2,846 ms. The import takes 8,843 ms against 91,728 ms.
- **Tests.** 142 tests; `bash ./gradlew build` passes in a fresh clone on JDK 21 and on JDK 17 in a container ([docs/experiments/ci-proof.txt](docs/experiments/ci-proof.txt) and the two logs next to it).
- **Known problem.** Concurrent cascading deletes deadlock: on 8 clients 79–83 of 100 account deletions and 4–6 of 8 deletions of imported videos fail after 3 attempts (README, known limitations; next step 2 below).
- **Not done.** Nothing has been pushed, so the CI workflow has never run on GitHub.

## Commands

```bash
./gradlew build                      # compile, Spotless check, all tests (needs Docker)
./gradlew :sustc-runner:bootJar      # run/sustc-runner.jar
scripts/benchmark.sh --label NAME    # fresh schema + one benchmark run -> docs/benchmark/NAME.json
python3 scripts/bench_table.py --write README.md README.zh-CN.md   # rebuild the README tables
scripts/explain_plans.sh --original-from SCHEMA                     # regenerate docs/explain
scripts/write_latency.sh             # regenerate docs/experiments/write-latency.txt
scripts/bench_series.sh --variant plain --prefix NAME   # interleaved B/O runs, docs/benchmark/round2/plain
scripts/bench_report.py docs/benchmark/round2/plain     # per-run sums, WAL per write step, checkpoints
scripts/writebench/run.sh --jar JAR --sql "FILES" --out OUT -- --mode steady|count|stress|reads
```

**Requirements.**
- The scripts need PostgreSQL on localhost, with user, password and database all `sustc`. Override them with `PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD` and `PGDATABASE`.
- They refuse any host other than localhost unless `--allow-remote` is given.
- They drop and recreate the `public` schema.
- On a shared machine, set `BENCH_LOCK=/path/to/lock` so that timed runs never overlap.

**Test data.** The course's `.ser` files are not in the repository. Put them in `sustc-runner/data/import/` and `sustc-runner/data/test/`.

**How the recorded runs were made.** The round-2 runs (`docs/benchmark/round2/{plain,settle,analyze}`) were made by `scripts/bench_series.sh`, which runs the harness script outside the repository (`/home/user/work/bench6/run_bench.sh`, under `flock`) through `scripts/bench_instrumented.py`, alternating the original (B, `/home/user/work/vs-baseline`) and this version (O). The WriteBench runs are in `docs/benchmark/round2/writebench`, with the exact options in each file. The round-1 runs are in `docs/benchmark/round1`. `scripts/benchmark.sh` does the same steps as the harness with the same JVM flags; `docs/benchmark/round1/dev/script-check.json` is one run of it.

## Where things are

| Path | Contents |
|---|---|
| `sql/build.sql` | tables, indexes, `count_occurrences`, derived tables and their triggers |
| `sql/audit.sql`, `sql/usercontrol.sql` | audit log on `users`; three roles and their grants |
| `sustc-api/.../service/impl/*ServiceImpl.java` | the services; each SQL statement is a commented `static final` text block |
| `sustc-api/.../service/impl/auth/` | `Authenticator` (one query in the common case, a second for `qq`/`wechat`) and `CurrentUser` |
| `sustc-api/.../service/impl/support/` | `VideoVisibility` (the one visibility predicate), `Page`, `SqlArrays`, `Strings` |
| `sustc-api/.../service/impl/importer/` | `BulkImporter` and its helpers (catalog-driven detach/restore, COPY writer, derived data) |
| `sustc-api/.../service/impl/config/ClockConfig.java` | the injectable `Clock`; `-Dsustc.clock.start=…` pins it |
| `sustc-api/src/test/` | `IntegrationTest` base class (singleton PostgreSQL 16 container, leak check), `Fixtures`, `DerivedTables.assertConsistent`, one test class per service |
| `docs/benchmark/` | raw benchmark reports: `round2/` (interleaved runs, WriteBench), `round1/` (earlier runs; `dev/`: single development runs) |
| `docs/explain/`, `docs/experiments/` | EXPLAIN outputs and the SQL that produced them; pgbench and timing experiments |
| `scripts/` | benchmark, series, report, table, EXPLAIN and write-latency scripts (`scripts/pgbench/`: the pgbench workloads; `scripts/writebench/`: WriteBench) |
| `sustc-runner/`, `io.sustc.service` interfaces, `io.sustc.dto` | the course framework: unchanged, and must stay so |

## Adding a REST layer

The services can be called from controllers, with these conditions:

- **Wiring.** They are Spring beans in `io.sustc.service.impl` (and its `config` package, which provides the `Clock`), with constructor injection. A new module (for example `sustc-web`) that depends on `sustc-api` must scan that package: `@SpringBootApplication(scanBasePackages = "io.sustc")`, or a `@Configuration` with `@ComponentScan("io.sustc.service.impl")`. Spring Boot's auto-configured `DataSource`, `JdbcTemplate` and `NamedParameterJdbcTemplate` are the only other beans they need.
- **Transactions.** The services are written for autocommit. Each call is atomic on its own: a write is one call, either one SQL statement or (for the toggles) a lock statement and the toggle sent together in one round trip, which PostgreSQL runs as one implicit transaction. Outside a transaction, `support/Writes` retries a call that loses a deadlock (up to 3 attempts). Inside a caller's transaction (a `TransactionTemplate` or `@Transactional` on the auto-configured `DataSourceTransactionManager`), each write runs under its own savepoint, so a refused write (`false`, `null`, `-1`) leaves the transaction usable; a deadlock still aborts the whole transaction, and the caller must retry it. The caller must also decide whether a refused write means that the whole transaction rolls back. The services set their own savepoints, so neither `PROPAGATION_NESTED` nor the JDBC option `autosave` is needed; do not use `autosave=conservative`. A toggle's advisory lock is held until the caller's transaction ends, so the same user's toggles wait for it.
- **Authentication.** It is per call, through `AuthInfo`, and every call needs real credentials. An `AuthInfo` with only a `qq` or `wechat` value authenticates without a password, so a web layer may build one only from a verified login with that provider (for example an OIDC token it has checked). Authentication costs one query in the common case and a second (`qq`, then `wechat`) when `mid` and password do not match. The passwords in the dataset are plaintext and must be hashed before any of this faces a network.
- **Errors.** The course interfaces report failure as `null`, `-1`, `false` or an empty list, without saying why. Mapping those to HTTP status codes needs richer return types; that would be a change to the interfaces, which are fixed for the course.
- **Time.** All time comes from the injected `Clock`, so controllers and tests can pin it. Times are stored as `timestamp` without time zone and converted in the JVM's default zone, so the importer and the server must run with the same `-Duser.timezone` (the benchmark uses Asia/Shanghai); otherwise move the columns to `timestamptz` and bind `OffsetDateTime`.
- **Capacity.**
  - The Hikari pool size is `spring.datasource.hikari.maximum-pool-size` (default 10).
  - The importer needs `sustc.import.parallelism` connections (default 4).
  - Search scans all videos with `count_occurrences`. That is fine for 44 videos, but a large catalogue needs a text index (for example `pg_trgm`) to pre-filter.
  - `video_coviews` grows with the number of video pairs that share a viewer, which in the worst case is quadratic in the number of videos.

## Open items

These are the follow-ups, in the order I would take them. The measurements behind them are in the notes.

1. **Run CI once pushed.** Compare its test times with the local ones.
2. **Serialize the cascading deletes.** On 8 clients, 4–6 of 8 concurrent deletions of imported videos and 79–83 of 100 account deletions fail with `deadlock detected` after 3 attempts ([WriteBench summary](docs/benchmark/round2/writebench/summary.md)). Take a transaction-scoped advisory lock in a key space of its own (`pg_advisory_xact_lock(1, 0)`) as the first statement of `deleteVideo` and `deleteAccount` when the row has derived data, or lock the affected `video_stats` and `video_coviews` rows in one global order first; then repeat the 8-client runs (`--clients 8 --only deleteAccount`, `--only deleteVideoWithViews`) and the benchmark series.
3. **Fold authentication into the write statements.** That saves one round trip per call (every write except register takes two round trips today, the authentication query and the write; WriteBench count mode).
4. **Purge old audit logs.** Logs written before `005b628` contain passwords, `qq` and `wechat`.
5. **Bound `video_coviews` for larger catalogues.** It holds up to N(N−1) rows, and a user's deletion changes k(k−1) of them for k watched videos; unordered pairs halve that, a cap per user bounds it.
6. **The first passes of danmu display.** In a fresh JVM the first pass over the benchmark's 382 display cases takes 114.6 ms here against 71.6 ms in the original, the fourth 36.2 against 34.9 ms ([WriteBench summary](docs/benchmark/round2/writebench/summary.md), reads mode). Find out which first-time cost differs (statement preparation, planning on each pooled connection, JIT); the server's share is small: a first execution in a fresh session took 0.21–0.36 ms against 0.10–0.15 ms for the next ones ([docs/explain/first-call.after.txt](docs/explain/first-call.after.txt)), on a pool of at most 10 connections.

Tried and dropped: an early exit in the derived-table triggers when a transition table is empty (measured, no gain; see the notes).
