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
| the next commit | the two READMEs, these notes, the raw benchmark reports in `docs/benchmark` |

Where it stands:
- **Benchmark.** All 5,622 cases pass in all three final runs. The 22 query and update steps take 3,923 ms against 15,196 ms for the original (medians of 3 runs), and the import takes 8,259 ms against 57,674 ms.
- **Tests.** `./gradlew build` passes on JDK 21, and on JDK 17 in a Docker container.
- **Not done.** Nothing has been pushed, so the CI workflow has never run on GitHub.

## Commands

```bash
./gradlew build                      # compile, Spotless check, 103 tests (needs Docker)
./gradlew :sustc-runner:bootJar      # run/sustc-runner.jar
scripts/benchmark.sh --label NAME    # fresh schema + one benchmark run -> docs/benchmark/NAME.json
python3 scripts/bench_table.py --write README.md README.zh-CN.md   # rebuild the README tables
scripts/explain_plans.sh --original-from SCHEMA                     # regenerate docs/explain
scripts/write_latency.sh             # regenerate docs/experiments/write-latency.txt
```

**Requirements.**
- The scripts need PostgreSQL on localhost, with user, password and database all `sustc`. Override them with `PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD` and `PGDATABASE`.
- They refuse any host other than localhost unless `--allow-remote` is given.
- They drop and recreate the `public` schema.
- On a shared machine, set `BENCH_LOCK=/path/to/lock` so that timed runs never overlap.

**Test data.** The course's `.ser` files are not in the repository. Put them in `sustc-runner/data/import/` and `sustc-runner/data/test/`.

**How the recorded runs were made.** The official runs (`docs/benchmark/baseline-*.json`, `optimized-*.json`) were made with a harness script outside the repository, under `flock`. `scripts/benchmark.sh` does the same steps with the same JVM flags. `docs/benchmark/dev/script-check.json` is one run of it on the final code: 5,622 passed, and 3,899 ms for steps 2–23.

## Where things are

| Path | Contents |
|---|---|
| `sql/build.sql` | tables, indexes, `count_occurrences`, derived tables and their triggers |
| `sql/audit.sql`, `sql/usercontrol.sql` | audit log on `users`; three roles and their grants |
| `sustc-api/.../service/impl/*ServiceImpl.java` | the services; each SQL statement is a commented `static final` text block |
| `sustc-api/.../service/impl/auth/` | `Authenticator` (one query per call) and `CurrentUser` |
| `sustc-api/.../service/impl/support/` | `VideoVisibility` (the one visibility predicate), `Page`, `SqlArrays`, `Strings` |
| `sustc-api/.../service/impl/importer/` | `BulkImporter` and its helpers (catalog-driven detach/restore, COPY writer, derived data) |
| `sustc-api/.../service/impl/config/ClockConfig.java` | the injectable `Clock`; `-Dsustc.clock.start=…` pins it |
| `sustc-api/src/test/` | `IntegrationTest` base class (singleton PostgreSQL 16 container, leak check), `Fixtures`, `DerivedTables.assertConsistent`, one test class per service |
| `docs/benchmark/` | raw benchmark reports (`dev/`: single development runs) |
| `docs/explain/`, `docs/experiments/` | EXPLAIN outputs and the SQL that produced them; pgbench and timing experiments |
| `scripts/` | benchmark, table, EXPLAIN and write-latency scripts (`scripts/pgbench/`: the pgbench workloads) |
| `sustc-runner/`, `io.sustc.service` interfaces, `io.sustc.dto` | the course framework: unchanged, and must stay so |

## Adding a REST layer

The services are ready to be called from controllers:

- **Wiring.** They are stateless Spring beans that implement the interfaces in `io.sustc.service`, with constructor injection. A new module (for example `sustc-web`) can depend on `sustc-api` and inject `VideoService` and the others directly. Spring Boot's auto-configured `DataSource` and `NamedParameterJdbcTemplate` are all they need.
- **Transactions.** Every service call is atomic on its own: each write is one SQL statement. Controllers need no `@Transactional`. To combine several calls atomically, wrap them in a `TransactionTemplate`; the auto-configured transaction manager binds the same connection to the `JdbcTemplate`.
- **Authentication.** It is per call, through `AuthInfo`. A web layer would map its session or token to an `AuthInfo`, or to a mid, after authenticating once. The passwords in the dataset are plaintext and must be hashed before any of this faces a network.
- **Errors.** The course interfaces report failure as `null`, `-1`, `false` or an empty list, without saying why. Mapping those to HTTP status codes needs richer return types; that would be a change to the interfaces, which are fixed for the course.
- **Time.** All time comes from the injected `Clock`, so controllers and tests can pin it.
- **Capacity.**
  - The Hikari pool size is `spring.datasource.hikari.maximum-pool-size` (default 10).
  - The importer needs `sustc.import.parallelism` connections (default 4).
  - Search scans all videos with `count_occurrences`. That is fine for 44 videos, but a large catalogue needs a text index (for example `pg_trgm`) to pre-filter.
  - `video_coviews` grows with the number of video pairs that share a viewer, which in the worst case is quadratic in the number of videos.

## Open items

These are the follow-ups, in the order I would take them. The measurements behind them are in the notes.

1. **Profile the two slower reads.** Danmu display and hotspots: their SQL takes about 0.13 ms per call, but the benchmark steps are slower than the original's.
2. **Exit early on empty transition tables.** A toggle fires both its insert and its delete trigger, and one of the two has nothing to do.
3. **Fold authentication into the write statements.** That saves one round trip per call.
4. **Checkpoint after a bulk import.** Writes right after an import pay for writeback and full-page images. `CHECKPOINT` needs `pg_checkpoint` membership, which the `sustc` role lacks.
5. **Run CI once pushed.** Compare its test times with the local ones.
