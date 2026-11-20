# Development

How to build, test and measure this branch, where things are, and how to put a REST layer on the services. The overview is in [README.md](../README.md), the reasoning and measurements are in [ENGINEERING_NOTES.md](ENGINEERING_NOTES.md), and the open items are in the README's [known limitations](../README.md#known-limitations).

## Commands

```bash
./gradlew build                      # compile, Spotless check, all tests (needs Docker)
./gradlew :sustc-runner:bootJar      # run/sustc-runner.jar
scripts/benchmark.sh --label NAME    # fresh schema + one benchmark run -> docs/benchmark/NAME.json
python3 scripts/bench_table.py --write README.md README.zh-CN.md   # rebuild the README tables
scripts/explain_plans.sh --original-from SCHEMA                     # regenerate docs/explain
scripts/write_latency.sh             # regenerate docs/experiments/write-latency.txt
python3 scripts/bench_report.py docs/benchmark/round3/plain        # per-run sums, WAL per write step, checkpoints
python3 -I scripts/compare.py b=B1.json,B2.json o=O1.json,O2.json  # per-step medians of benchmark reports
python3 scripts/writebench/tables3.py > docs/benchmark/round3/writebench/summary.md   # WriteBench tables of a directory
scripts/writebench/run.sh --jar JAR --sql "FILES" --out OUT -- --mode steady|count|stress|reads
```

The interleaved series (B: the 2023 implementation, O: this checkout; 3 rounds of B then O on one boot), as recorded in `docs/benchmark/round{2,3}/plain`:

```bash
BASELINE=/path/to/baseline-checkout scripts/bench_series.sh --variant plain --prefix NAME --round round4
```

`BASELINE` (or `--baseline`, default `/home/user/work/vs-baseline`) is a checkout of the branch `bench/baseline-clock` (`3796695`: `main`'s service code plus the clock offset described in the README). Both checkouts need their jar in `run/` (`./gradlew :sustc-runner:bootJar`). Each run goes through `scripts/bench_instrumented.py`, which holds an exclusive flock on `BENCH_LOCK` (default `/home/user/work/bench.lock`) for the run and its WAL reading, and calls the harness `BENCH_HARNESS` (default `scripts/run_bench.sh`), which writes its raw files to `BENCH_OUT` (default `/home/user/work/bench6`). Results go to `docs/benchmark/<round>/<variant>/`.

**Requirements.**
- The scripts need PostgreSQL on localhost, with user, password and database all `sustc` (`sustc-runner/compose.yml` starts one). Override them with `PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD` and `PGDATABASE` (`scripts/benchmark.sh`; the series scripts use localhost:5432).
- They refuse any host other than localhost unless `--allow-remote` is given.
- They drop and recreate the `public` schema.
- On a shared machine, set `BENCH_LOCK=/path/to/lock` so that timed runs never overlap.
- `scripts/bench_instrumented.py`, WriteBench's `--settle` and the WAL experiments run `CHECKPOINT` and `pg_waldump` through `sudo -u postgres`.

**Test data.** The course's `.ser` files are not in the repository. Put them in `sustc-runner/data/import/` and `sustc-runner/data/test/`.

**How the recorded runs were made.** The interleaved series in `docs/benchmark/round2/{plain,settle,analyze}` and `docs/benchmark/round3/plain` were made by `scripts/bench_series.sh` through `scripts/bench_instrumented.py`, with the harness then outside the repository (`/home/user/work/bench6/run_bench.sh`, of which `scripts/run_bench.sh` is a copy that differs only in its output directory), alternating the 2023 implementation (B, `/home/user/work/vs-baseline`) and this version (O). At that time `bench_instrumented.py` recorded the commit, working-tree state and jar hash before it waited for the lock; it now records them once it holds the lock. The WriteBench runs are in `docs/benchmark/round{2,3,4}/writebench`, with the exact options in each JSON file (from round 4 on also git HEAD, the porcelain status and the SHA-256 of every SQL file, taken inside the lock). The round-1 runs are in `docs/benchmark/round1`. `scripts/benchmark.sh` does the same steps as the harness with the same JVM flags; `docs/benchmark/round1/dev/script-check.json` is one run of it.

## Where things are

| Path | Contents |
|---|---|
| `sql/build.sql` | tables, indexes, `count_occurrences`, derived tables and their triggers |
| `sql/audit.sql`, `sql/usercontrol.sql` | audit log on `users`; three example roles and their grants (checked by `UserControlScriptTest`) |
| `sustc-api/.../service/impl/*ServiceImpl.java` | the services; each SQL statement is a commented `static final` text block |
| `sustc-api/.../service/impl/auth/` | `Authenticator` (one query in the common case, a second for `qq`/`wechat`) and `CurrentUser` |
| `sustc-api/.../service/impl/support/` | `VideoVisibility` (the one visibility predicate), `Writes` (deadlock retries, savepoints, the toggle and deletion locks), `Page`, `SqlArrays`, `Strings`, `Timestamps` |
| `sustc-api/.../service/impl/importer/` | `BulkImporter` and its helpers (catalog-driven detach/restore, COPY writer, derived data) |
| `sustc-api/.../service/impl/config/ClockConfig.java` | the injectable `Clock`; `-Dsustc.clock.start=…` pins it |
| `sustc-api/src/test/` | `IntegrationTest` base class (singleton PostgreSQL 16 container, leak check), `Fixtures`, `DerivedTables.assertConsistent`, one test class per service, `ConcurrencyTest`, `UserControlScriptTest` |
| `docs/benchmark/` | raw benchmark reports, by round (defined in the README's [methodology](../README.md#methodology)): `round4/` (WriteBench on the final code), `round3/` (the interleaved series quoted in the README, and WriteBench), `round2/` (the previous series, the settle and analyze variants, WriteBench), `round1/` (earlier runs; `dev/`: single development runs); [docs/benchmark/README.md](benchmark/README.md) explains the environment strings in the raw files |
| `docs/explain/`, `docs/experiments/` | EXPLAIN outputs and the SQL that produced them; pgbench and timing experiments |
| `scripts/` | benchmark, series, report, table, EXPLAIN and write-latency scripts; `run_bench.sh` and `compare.py`, the vendored harness of the recorded series (`scripts/pgbench/`: the pgbench workloads; `scripts/writebench/`: WriteBench) |
| `sustc-runner/`, `io.sustc.service` interfaces, `io.sustc.dto` | the course framework: unchanged, and must stay so |

## Adding a REST layer

The services can be called from controllers, with these conditions:

- **Wiring.** They are Spring beans in `io.sustc.service.impl` (and its `config` package, which provides the `Clock`), with constructor injection. A new module (for example `sustc-web`) that depends on `sustc-api` must scan that package: `@SpringBootApplication(scanBasePackages = "io.sustc")`, or a `@Configuration` with `@ComponentScan("io.sustc.service.impl")`. Spring Boot's auto-configured `DataSource`, `JdbcTemplate` and `NamedParameterJdbcTemplate` are the only other beans they need.
- **Transactions.** The services are written for autocommit. Each call is atomic on its own: after the authentication query, a write is one SQL statement or (for the toggles and the deletions) a lock statement and the write sent together in one round trip, which PostgreSQL runs as one implicit transaction. Outside a transaction, `support/Writes` retries a call that loses a deadlock (up to 3 attempts). Inside a caller's transaction (a `TransactionTemplate` or `@Transactional` on the auto-configured `DataSourceTransactionManager`), each write runs under its own savepoint, so a refused write (`false`, `null`, `-1`) leaves the transaction usable; a deadlock still aborts the whole transaction, and the caller must retry it. The caller must also decide whether a refused write means that the whole transaction rolls back. The services set their own savepoints, so neither `PROPAGATION_NESTED` nor the JDBC option `autosave` is needed; do not use `autosave=conservative`. A toggle's advisory lock is held until the caller's transaction ends, so the same user's toggles wait for it; likewise the deletion lock, so while a caller's transaction that deleted an account is open, no other deletion that changes derived rows can run. The derived-table triggers need READ COMMITTED, PostgreSQL's default: `derived_viewers` refuses to change views under REPEATABLE READ, and SERIALIZABLE is untested.
- **Authentication.** It is per call, through `AuthInfo`, and every call needs real credentials. An `AuthInfo` with only a `qq` or `wechat` value authenticates without a password, so a web layer may build one only from a verified login with that provider (for example an OIDC token it has checked). Authentication costs one query in the common case and a second (`qq`, then `wechat`) when `mid` and password do not match. The passwords in the dataset are plaintext and must be hashed before any of this faces a network.
- **Errors.** The course interfaces report failure as `null`, `-1`, `false` or an empty list, without saying why. Mapping those to HTTP status codes needs richer return types; that would be a change to the interfaces, which are fixed for the course.
- **Time.** All time comes from the injected `Clock`, so controllers and tests can pin it. Times are stored as `timestamp` without time zone and converted in the JVM's default zone, so the importer and the server must run with the same `-Duser.timezone` (the benchmark uses Asia/Shanghai); otherwise move the columns to `timestamptz` and bind `OffsetDateTime`.
- **Capacity.**
  - The Hikari pool size is `spring.datasource.hikari.maximum-pool-size` (default 10).
  - The importer needs `sustc.import.parallelism` connections (default 4).
  - Search scans all videos with `count_occurrences`. That is fine for 44 videos, but a large catalogue needs a text index (for example `pg_trgm`) to pre-filter.
  - `video_coviews` grows with the number of video pairs that share a viewer, which in the worst case is quadratic in the number of videos.
