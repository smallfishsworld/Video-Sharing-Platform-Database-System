# Engineering notes

This file covers how the original course implementation (commit `2e811bd`) became the current one: what changed, why, what was measured, and how the specification was read where it is ambiguous. The [README](../README.md) has the overview and the benchmark table; this file has the detail behind them.

All timings come from one machine: 4 vCPUs, 16 GB of RAM, and PostgreSQL 16 with `shared_buffers` 1 GB, `synchronous_commit` on and JIT on. Each run held an exclusive lock, so no other benchmark overlapped it. Every number below names the file it comes from.

## Contents

1. [Starting point](#starting-point)
2. [Schema](#schema)
3. [Service layer](#service-layer)
4. [Bulk import](#bulk-import)
5. [Derived tables](#derived-tables)
6. [Query plans](#query-plans)
7. [The write path](#the-write-path)
8. [Planner statistics and the unstable friend step](#planner-statistics-and-the-unstable-friend-step)
9. [Development checkpoints](#development-checkpoints)
10. [Tests](#tests)
11. [Tried and rejected](#tried-and-rejected)
12. [Open questions](#open-questions)
13. [Interpretations of the specification](#interpretations-of-the-specification)

## Starting point

The original implementation passes 5,618 of the 5,622 benchmark cases in each of its three runs (`docs/benchmark/baseline-r*.json`). The benchmark logs and the code at `2e811bd` show why. They also show problems that the benchmark does not check:

- **The four failures.** Three searches fail because `keywords.split(" ")` turns repeated spaces into empty keywords. `regexp_split_to_array(title, '')` then splits the title into single characters, so every video matches. The keywords are also used as regular expressions. The fourth failure is one view rate that is off in the fourth digit (expected 0.5450836, got 0.5451384): view times are stored as `real` and summed with `sum(real)`. The original reads `Instant.now()`. The build of it that the harness measured adds a constant offset, kept outside this repository, so it starts at the same pinned time as the current code.
- **Writes are sequences of autocommit statements.** `coinVideo` reads the balance, checks for an earlier donation, inserts the donation and then writes back a balance computed in Java. Two concurrent donations by one user can therefore spend the same coin. `follow` returns `true` even when it unfollows.
- **No foreign keys.** Deleting a video leaves its danmu likes behind. Deleting a user leaves everything that other users did on that user's videos and danmus.
- **Read paths aggregate from scratch.**
  - General recommendations hash-aggregate all five interaction tables (2.2 M rows) on every call.
  - Similar videos self-join `viewers`.
  - Friend recommendations scan all of `followings` (6.0 M rows).
- **Connections leak on errors.** `UserServiceImpl`, `DanmuServiceImpl` and `RecommenderServiceImpl` have no `finally` block and no try-with-resources. They close their connection only on the success path, so an exception leaks it. `follow`, for example, rethrows an `SQLException` without closing anything.
- **Import.** The import uses batched `INSERT`s on four threads into tables whose indexes already exist. No `ANALYZE` follows it, and it takes 57.7 s (median).

## Schema

[`sql/build.sql`](../sql/build.sql), commit `66edb47`, with the derived tables added in `95a7e83`.

- **Keys in both directions.** Each relation table (`viewers`, `videolikes`, `coins`, `favorites`) has the primary key `(bv, mid)` and a secondary index `(mid, bv)`. `followings` has `(follower_mid, followee_mid)` and `(followee_mid, follower_mid)`. Every lookup is therefore a range of one B-tree, and every "count per X" can be an index-only scan. That holds from the start, because the importer leaves the visibility map set (see [Bulk import](#bulk-import)). In the "after" plans in `docs/explain`, every index-only scan but one reports `Heap Fetches: 0`. The exception reports 9, for 336 rows (`user-rec.after.txt`).
- **Foreign keys with `ON DELETE CASCADE`** on every reference except `videos.reviewer`, which is set to `NULL` when the reviewer is deleted, so the video stays. Deleting a user or a video becomes one statement, and the database, not the Java code, decides what "everything that refers to it" means. Indexes on `danmus (mid)`, `danmulikes (mid)` and `videos (reviewer)` exist only to make those cascades index lookups.
- **Types.** View times, durations and danmu times are `double precision`, which fixes the view-rate failure. `users.mid` and `danmus.id` are identity columns. `users.coin` has `CHECK (coin >= 0)`.
- **Uniqueness.** `qq` and `wechat` are unique and `NULL` when absent. `videos (owner_mid, title)` is unique, which is how "the user already has a video with this title" is enforced. A conflict makes the `INSERT` return no row, with no separate check and no race.
- **`count_occurrences(haystack, needle)`** is an immutable SQL function, `(length(h) - length(replace(h, n, ''))) / length(n)`. `replace()` removes the occurrences from left to right without overlaps. That is the counting rule of the search specification, and the Javadoc examples are unit tests (`VideoServiceTest`).

## Service layer

Commit `66edb47`. Each service in `io.sustc.service.impl` is a Spring bean with constructor injection of `NamedParameterJdbcTemplate`, `Authenticator` and `Clock`. There is no ORM and no `@Transactional`. The SQL lives in `static final` text blocks in each service, each with a comment on what it guarantees.

- **One statement per write.** Toggles, coin, review, register, post, delete and follow are each one statement. The building blocks are data-modifying CTEs, `INSERT … ON CONFLICT DO NOTHING RETURNING`, and conditions in the `WHERE` clause of an `UPDATE`.
  - Atomicity comes from the statement, not from a transaction around several.
  - Races between "check" and "act" disappear, because the check is part of the act.
  - Each write is one round trip after authentication.
  - Coin donation (`VideoServiceImpl.DONATE_COIN`) is shaped like this:
    ```sql
    with target as (select v.bv from videos v
                    where v.bv = :bv and v.owner_mid <> :me and <visible to me>
                      and not exists (select 1 from coins c where c.bv = v.bv and c.mid = :me)),
         charged as (update users set coin = coin - 1
                     where mid = :me and coin > 0 and exists (select 1 from target)
                     returning mid)
    insert into coins (bv, mid) select target.bv, charged.mid from target, charged
    ```
    - The row lock taken by the `UPDATE` serializes donations by one user, and PostgreSQL re-checks `coin > 0` on the newest version of the row.
    - A concurrent second donation to the same video fails on the `coins` primary key, and the whole statement, charge included, rolls back.
    - `VideoServiceTest` checks both cases with 8 threads. Donating to one video from an account with 10 coins, exactly one call succeeds and the balance is 9. Donating to 8 videos from an account with 1 coin, exactly one call succeeds and the balance is 0.
- **Authentication** (`auth/Authenticator`) is one query. It returns the user's id and identity together, so the superuser check costs nothing extra. If both `qq` and `wechat` are given, they must belong to the same user.
- **Visibility** (`support/VideoVisibility`) is one SQL predicate shared by search, like, coin and collect. A video is *published* once it is reviewed and its public time, if any, has passed. Unpublished videos are visible only to their owner and to superusers.
- **Time** comes from the injected `Clock` (`config/ClockConfig`). `-Dsustc.clock.start=…` starts that clock at a given instant; the benchmark data assumes 2023-12-30 12:00 +08:00.
- **Paging** (`support/Page`) validates sizes and numbers in one place and computes the offset in `long`, so large page numbers cannot overflow.
- **Logging** goes through slf4j (Lombok `@Slf4j`); there are no `System.out` calls.

## Bulk import

`importer/BulkImporter`, `DeferredSchemaObjects`, `DerivedData`, `CopyRowWriter`, `ParallelTasks`. Commit `66edb47` and later. The steps:

1. **Detach.** The importer reads the indexes, primary and unique constraints, foreign keys and `derived_*` triggers of every table from the catalog, keeps their definitions (`pg_get_indexdef`, `pg_get_constraintdef`, `pg_get_triggerdef`) and drops them. Nothing about the schema is hard-coded. A column or index added to `build.sql` is handled without touching the importer.
2. **Load.** Each table is loaded on its own connection by a `TRUNCATE` and a `COPY … FROM STDIN WITH (FREEZE)` in one transaction. `CopyRowWriter` streams text-format rows through pgjdbc's `CopyManager`. `FREEZE` writes the rows already frozen and marks the pages all-visible, so index-only scans work right after the import with no `VACUUM` pass. `DerivedData` computes `video_stats` and `video_coviews` from the records in memory, and they are loaded the same way.
3. **Indexes.** The importer recreates the indexes in parallel, largest table first, and re-attaches the primary and unique constraints to them (`ADD CONSTRAINT … USING INDEX`).
4. **Constraints.** The foreign keys are added `NOT VALID` and then validated, one table per task, and the triggers are recreated.
5. **Statistics.** `VACUUM (ANALYZE)` runs per table, and `setval` moves the identity sequences past the imported ids.

The pool has `sustc.import.parallelism` threads (default 4), each with one connection.

**Phase times** come from the three final runs (`Import phase …` lines in their logs):

| Phase | r1 | r2 | r3 |
|---|---:|---:|---:|
| load | 2,331 ms | 2,460 ms | 2,279 ms |
| indexes | 4,629 ms | 4,306 ms | 4,345 ms |
| constraints | 1,141 ms | 1,196 ms | 1,139 ms |
| vacuum analyze | 215 ms | 207 ms | 229 ms |

The benchmark step for the import takes 8,259 ms (median). That includes the benchmark reading its `.ser` files.

**Failure handling.**
- If any step fails, the importer truncates every table and restores whatever schema objects are missing, then rethrows. The database is left empty with its complete schema.
- `BulkImporterTest` injects a foreign-key violation and a duplicate key, then compares the catalog with a snapshot taken before the import.
- This test found a bug, now fixed. After a failed validation, the foreign keys that had been added `NOT VALID` stayed unvalidated: 8 of them, in the run that exposed it. `restoreMissing` now validates every foreign key still marked `NOT VALID` (`DeferredSchemaObjects.NOT_VALIDATED_FOREIGN_KEYS`).

Imported row counts: users 37,881; videos 44; followings 5,958,770; viewers 874,856; videolikes 480,154; coins 447,153; favorites 430,622; danmus 322; danmulikes 11,068; video_stats 44; video_coviews 1,892 (`Imported …` line of `optimized-r1`'s log).

## Derived tables

Commit `95a7e83`. There are two tables:

- `video_stats` holds per-video counters: viewers, the sum of view times, likes, coins, favorites and danmus.
- `video_coviews` holds, for every ordered pair of different videos, the number of users who watched both. Both directions are stored, so the neighbours of a video are one index range.

**Maintenance.** Statement-level `AFTER` triggers keep both tables exact inside the writing transaction:
- `derived_videos` creates the stats row.
- `derived_interactions` handles likes, coins, favorites and danmus.
- `derived_viewers` handles views and co-views.

**Design choices:**

- **Statement level, with transition tables.** Deleting a user cascades to thousands of rows, and a row-level trigger would run thousands of times. Here each trigger reads `old_rows`/`new_rows` once and applies one set-based change.
- **One sorted upsert per change.** Counters move by signed deltas in an `INSERT … ON CONFLICT DO UPDATE`, ordered by `bv`. Two triggers that touch the same counters therefore lock them in the same order. Rows of videos that are being deleted are skipped; their counters go with them.
- **No `CHECK (>= 0)` on the counters.** PostgreSQL checks the row an `INSERT … ON CONFLICT` proposes before it finds the conflict, so a negative delta would fail even though it only ever becomes an update. The tests compare the counters with a full recomputation instead.
- **Co-views.**
  - When a user's views of a set of videos appear, every pair between those videos and the user's other videos gains one. Pairs within the set gain one too.
  - When the views disappear, the same pairs lose one. Pairs that reach zero are deleted, so `video_coviews` has no zero rows (`CHECK (viewers > 0)`).
  - Updating the `(bv, mid)` key of a view is rejected: a different key is a different viewer.
- **`TRUNCATE` fires no row-level work**, so `DatabaseService.truncate()` truncates the derived tables together with everything else.

**Cost.** The read paths that use these tables are in [Query plans](#query-plans), and what the triggers cost per write is in [The write path](#the-write-path).

## Query plans

The query plans are produced by `EXPLAIN (ANALYZE, BUFFERS)` on the same data, in [`docs/explain`](explain). `scripts/explain_plans.sh` regenerates them, and `explain.sql` documents the inputs.
- "Before" runs the original queries on schema `original`. It is the original `build.sql` with its indexes, holding the rows of the benchmark import, analyzed.
- "After" runs the current queries on `public`, freshly imported.

Before reading the times, note that `EXPLAIN ANALYZE` times every plan node, which adds cost to every row a plan produces. On a 638 k-row join and aggregation here it reported 200–205 ms, against 129–133 ms with `TIMING OFF` and 122–124 ms run plainly (three runs each) ([`experiments/explain-overhead.txt`](experiments/explain-overhead.txt); `pg_test_timing`: 27 ns per clock read). The row-heavy "before" plans therefore look slower here than they run, and the ratios between single queries overstate the gain. The benchmark table in the README is the end-to-end measure.

### Similar videos

Before (`similar-videos.before.txt`), a parallel self-join of `viewers`:

```
 Limit  (actual time=160.662..164.467 rows=5 loops=1)
         ->  Finalize GroupAggregate  (actual time=160.614..164.450 rows=43 loops=1)
                           ->  Partial HashAggregate  (actual time=154.837..154.845 rows=42 loops=3)
                                 ->  Parallel Hash Join  (actual time=7.345..123.586 rows=262969 loops=3)
                                       ->  Parallel Seq Scan on viewers a  (actual time=0.011..46.890 rows=279746 loops=3)
 Execution Time: 164.538 ms
```

After (`similar-videos.after.txt`), the video's 43 co-view rows and a top-5 sort, with ties broken by `other_bv`:

```
     ->  Limit  (actual time=0.029..0.030 rows=5 loops=1)
           ->  Sort  (actual time=0.028..0.029 rows=5 loops=1)
                 ->  Bitmap Heap Scan on video_coviews c  (actual time=0.013..0.017 rows=43 loops=1)
                       ->  Bitmap Index Scan on video_coviews_pkey  (actual time=0.009..0.009 rows=43 loops=1)
 Execution Time: 0.059 ms
```

### General recommendations

Before (`general-rec.before.txt`), one hash aggregate per interaction table, 2.2 M rows in all, merge-joined on `bv`:

```
 Limit  (actual time=725.067..725.091 rows=10 loops=1)
                                             ->  HashAggregate  (actual time=296.693..296.704 rows=44 loops=1)
                                                         ->  Seq Scan on viewers r  (actual time=0.003..50.654 rows=874856 loops=1)
                                             ->  HashAggregate  (actual time=150.179..150.186 rows=44 loops=1)
                                                         ->  Seq Scan on videolikes r_1  (actual time=0.002..26.928 rows=480154 loops=1)
                                       ->  HashAggregate  (actual time=140.537..140.545 rows=44 loops=1)
                                                   ->  Seq Scan on coins r_2  (actual time=0.002..24.863 rows=447153 loops=1)
                                 ->  HashAggregate  (actual time=136.865..136.872 rows=44 loops=1)
                                             ->  Seq Scan on favorites r_3  (actual time=0.008..24.765 rows=430622 loops=1)
 Execution Time: 725.281 ms
```

After (`general-rec.after.txt`), the score from `video_stats`, with ties broken by `bv`:

```
 Limit  (actual time=0.075..0.077 rows=10 loops=1)
         ->  Hash Join  (actual time=0.022..0.051 rows=44 loops=1)
               ->  Seq Scan on videos v  (actual time=0.002..0.006 rows=44 loops=1)
                     ->  Seq Scan on video_stats s  (actual time=0.002..0.006 rows=44 loops=1)
 Execution Time: 0.094 ms
```

**User recommendations.** The single query barely changes: 1.690 ms → 0.963 ms for mid 422017, the case whose friends watched the most (`user-rec.*.txt`). But 13 of the 22 cases with valid credentials have no friends and fall back to the general recommendation. That is why the benchmark step goes from 1,580 ms to 56 ms.

### Friend recommendations

The largest case: mid 882400 follows 1,762 accounts. Before (`friend-rec.before.txt`), a parallel scan of all 6 M follow rows, hash-joined to the user's followings:

```
                           ->  Finalize HashAggregate  (actual time=417.732..420.612 rows=23059 loops=1)
                                             ->  Hash Join  (actual time=0.451..386.265 rows=92572 loops=3)
                                                   ->  Parallel Seq Scan on followings b  (actual time=0.009..183.425 rows=1985669 loops=3)
 Execution Time: 440.842 ms
```

After (`friend-rec.after.txt`): for each account the user follows, an index-only range of `followings_followee_idx` (its other followers). Then the aggregation, and then the anti-join that removes accounts the user already follows:

```
                           ->  HashAggregate  (actual time=99.925..102.756 rows=23059 loops=1)
                                 ->  Nested Loop  (actual time=0.029..56.403 rows=277715 loops=1)
                                       ->  Index Only Scan using followings_pkey on followings mine  (actual time=0.015..0.204 rows=1762 loops=1)
                                       ->  Index Only Scan using followings_followee_idx on followings o  (actual time=0.006..0.019 rows=158 loops=1762)
 Execution Time: 121.774 ms
```

The median case (mid 1727180, 43 followings) goes from 330.955 ms to 11.032 ms. On tables that were never analyzed, the original plan took 608.395 ms and 662.909 ms for the two cases (`friend-rec.before-nostats.txt`); see [the friend step](#planner-statistics-and-the-unstable-friend-step).

### Search

The keywords "周杰伦 MV" are searched by a regular user. Before (`search.before.txt`), the original joins every view of every matching video to count views (51,157 joined rows for 2 matches), and splits titles with the keyword as a regular expression:

```
                           ->  Nested Loop Left Join  (actual time=0.505..7.840 rows=51157 loops=1)
                                 ->  Index Only Scan using viewers_i1 on viewers r  (actual time=0.014..1.571 rows=25578 loops=2)
 Execution Time: 13.944 ms
```

After (`search.after.txt`), relevance from `count_occurrences` over the 44 visible videos, and views from `video_stats`:

```
         ->  Nested Loop  (actual time=0.202..0.337 rows=2 loops=1)
                     ->  Seq Scan on videos v  (actual time=0.004..0.013 rows=44 loops=1)
                           ->  Seq Scan on video_stats s  (actual time=0.002..0.005 rows=44 loops=1)
               ->  Aggregate  (actual time=0.007..0.007 rows=0 loops=44)
 Execution Time: 0.373 ms
```

## The write path

Eleven write steps are slower in the benchmark than in the baseline, by 11 to 309 ms per step. For example, like goes from 266 to 575 ms over 686 calls, and delete video from 287 to 529 ms over 55. There are two measurements of where the time goes.

**One write, with EXPLAIN** (`explain/write-path.after.txt`, rolled back):

| Write | Total | Inside it |
|---|---:|---|
| like toggle (insert) | 1.348 ms | `derived_videolikes_delete` 0.564 ms, `derived_videolikes_insert` 0.234 ms, foreign-key checks 0.124 + 0.064 ms |
| coin donation | 0.704 ms | `users_audit_update` 0.313 ms, `derived_coins_insert` 0.271 ms |
| delete a video with 27,136 views | 40.955 ms | cascades to viewers 14.821, videolikes 5.719, favorites 5.446, coins 5.129 ms; `derived_viewers_delete` 3.412 ms |

The toggle is one statement with a `DELETE` and an `INSERT` CTE, so both statement-level triggers fire, even the one whose transition table is empty. In this one-off session the times also include the first execution of each PL/pgSQL function. The pgbench numbers below are the steady state.

**Repeated writes with pgbench** (`experiments/write-latency.txt`, `scripts/write_latency.sh`). Each line is 1,000 transactions on one client, using authentication and the toggle as the service runs them:

| likeVideo | Latency |
|---|---:|
| right after a fresh import (Dirty: 142,768 kB) | 1.301 ms |
| 120 s later (Dirty: 316 kB) | 0.783 ms |
| without the derived-table triggers | 0.687 ms |
| … and without the foreign keys | 0.803 ms |
| with both again | 0.877 ms |
| original statement sequence vs current, alternating rounds | 1.235 / 1.057 / 0.941 vs 0.882 / 0.904 / 0.806 ms |

**What the measurements show:**
- At steady state, a write here is faster than the original's five statements.
- The triggers cost about 0.1–0.2 ms.
- The foreign keys cost nothing that this measurement can separate from noise. The run without them was not faster.
- What does cost time is timing. The benchmark starts its write steps a few seconds after an import that now ends about 50 s sooner. At that point the import's dirty pages are still being written back, and a checkpoint is running. During a checkpoint, the first change to each page writes a full-page image to the WAL.

**The deletes also do more work.** They now remove what the original left behind. The baseline's delete steps were measured on a schema without foreign keys.

**Reads are not affected.** `displayDanmu` takes 0.126 ms right after the import and 0.141 ms later.

## Planner statistics and the unstable friend step

The baseline's friend step took 1,932, 8,910 and 9,008 ms in its three runs. That is the widest range in the table, and the reason the baseline median is 8.9 s.
- The original importer never runs `ANALYZE`, so whether `followings` has statistics when step 8 starts depends on whether autovacuum has analyzed it by then.
- Without statistics, the original plans take 608 and 663 ms instead of 331 and 441 ms (`friend-rec.before-nostats.txt` against `friend-rec.before.txt`).
- That supports the explanation but does not prove it. Per query, the slowdown is 1.5–1.8×, while the step slowed by 4.6×. The step runs its 142 calls up to four at a time, and the parallel plans compete for the same cores, which could amplify the slowdown; that was not measured.
- Compared with the fast baseline run (1,932 ms), the friend step is 3.5× faster. Compared with the median, it is 16×.

The current importer ends with `VACUUM (ANALYZE)`. In the three final runs the step took 523–628 ms.

## Development checkpoints

These are single benchmark runs made with the same harness during development, each on the working tree just before the commit named. They are not repeated, and the write steps vary a lot between identical runs (over the three final runs, like took 571–837 ms and coin 374–1,090 ms), so only the large changes mean anything. Raw files: [`benchmark/dev`](benchmark/dev).

| Step (ms) | baseline, median | m1 core (`66edb47`) | m2 derived | m3 upsert | m4 friends (`95a7e83`) | final, median |
|---|---:|---:|---:|---:|---:|---:|
| 1 import | 57,674 | 10,624 | 7,819 | 8,204 | 7,928 | 8,259 |
| 2 search | 197 | 161 | 30 | 33 | 31 | 39 |
| 5 similar videos | 1,736 | 2,231 | 23 | 17 | 15 | 24 |
| 6 general rec | 831 | 515 | 4 | 6 | 7 | 7 |
| 7 user rec | 1,580 | 1,137 | 57 | 46 | 47 | 56 |
| 8 friend rec | 8,910 | 708 | 668 | 587 | 532 | 549 |
| 14 like | 266 | 818 | 748 | 487 | 695 | 575 |
| 21 delete video | 287 | 392 | 707 | 586 | 600 | 529 |
| sum of steps 2–23 | 15,196 | 8,568 | 4,671 | 4,046 | 4,386 | 3,923 |
| passed (of 5,622) | 5,618 | 5,622 | 5,622 | 5,622 | 5,622 | 5,622 |

What each checkpoint changed:

- **m1 core.** The rewritten services, the new schema and the `COPY` importer with `ANALYZE`. All cases pass, the import is 5× faster, and the friend step no longer depends on whether autovacuum has analyzed `followings`. Similar videos and the recommendations still aggregate from scratch.
- **m2 derived.** The derived tables and their triggers. Similar videos, general and user recommendations, and search drop to tens of milliseconds. Deletes get slower, because they now maintain counters.
- **m3 upsert.** The counter triggers became one sorted upsert each, and the counter `CHECK`s were dropped.
- **m4 friends.** The friend query applies the anti-join after the aggregation, which was the cheapest of the variants compared over all 133 valid calls. The viewers delete trigger became a single pass.

`docs/benchmark/dev/script-check.json` is one run of `scripts/benchmark.sh` on the final code. It shows that the script in this repository reproduces the procedure: 5,622 / 5,622 passed, an import of 8,471 ms, and 3,899 ms for steps 2–23.

Command: `cd /home/user/work/bench6 && python3 -I compare.py m1=m1_core.json m2=m2_derived.json m3=m3_upsert.json m4=m4_friends.json` (the same files as in `docs/benchmark/dev`).

## Tests

There are 103 tests (JUnit 5): `./gradlew build` runs them, and CI runs them on JDK 17 and 21.

**Setup.**
- Integration tests extend `IntegrationTest`. It starts one `postgres:16-alpine` container for the whole run (Testcontainers 1.21.4) and wires it in with `@DynamicPropertySource`.
- The container runs with `fsync`, `synchronous_commit` and `full_page_writes` off, which is safe for a disposable test database.
- The schema is created once from `sql/`, and every test starts from truncated tables.
- After each test, `noConnectionIsLeaked()` asserts that the Hikari pool has no active connection.

| Class | Tests | What it covers |
|---|---:|---|
| `UserServiceTest` | 27 | registration rules, authentication, deletion rights and cascades, follow state, user info |
| `VideoServiceTest` | 20 | post/update/review/delete, visibility, search relevance (the Javadoc examples), view rate, coin/like/collect, concurrent coin donations |
| `DanmuServiceTest` | 8 | send rules, display ranges, the earliest-posted filter, danmu likes |
| `RecommenderServiceTest` | 9 | similar videos with ties, general score with clamping, friends' videos, friend recommendations and their tie-breaks |
| `DerivedDataConsistencyTest` | 5 | randomized workloads (seeds 1, 20231230, 424242; 400 steps each; 18 kinds of service call or multi-row SQL). Every 50 steps, `video_stats` and `video_coviews` must equal a recomputation from scratch, and each kind of action must have changed something. Also covers a rejected key update and stats rows following their videos |
| `BulkImporterTest` | 5 | stored data equals the input, sequences continue after the import, the catalog is unchanged, statistics exist, non-empty tables are refused, and failure leaves an empty database with the complete schema |
| `DatabaseServiceTest` | 3 | truncate, group members, the sum helper |
| `ServiceHelpersTest`, `ClockConfigTest`, `PageTest` | 26 | keyword splitting, birthdays, sex labels, the pinned clock, paging arithmetic |

All 103 pass on JDK 21 on the host and on JDK 17 in an `eclipse-temurin:17-jdk` container. The GitHub Actions workflow (`.github/workflows/ci.yml`) has not run yet, because nothing has been pushed.

## Tried and rejected

- **Dropping the foreign keys for write speed.** pgbench shows no gain (0.803 ms without them against 0.687 ms before removing them). Without the foreign keys, the cascades would have to be written by hand again.
- **`CHECK (>= 0)` on the counters.** These are incompatible with signed-delta upserts; see [Derived tables](#derived-tables).
- **Rebuilding `video_coviews` in SQL after the load.** That needs a self-join of all 875 k views on the user. `DerivedData` instead counts the pairs in Java, during the load phase, while the records are in memory.
- **Not tried: server settings.** Parallel query, JIT and the checkpoint settings stay as the shared server has them for every run.

## Open questions

- **Two cheap reads are slower in the benchmark.** Hotspots go from 10 to 27 ms over 56 calls, and danmu display from 34 to 77 ms over 382 calls. In the database, display takes 0.13–0.14 ms per call (pgbench), so the extra time is probably per call in the Java layer: named-parameter parsing, array decoding, or statement preparation. That is not proven, and profiling it is the next step.
- **Writes right after the import.** A checkpoint at the end of the import would remove the post-import penalty measured above. `CHECKPOINT` needs a superuser or `pg_checkpoint` membership, and the `sustc` role has neither.
- **Authentication is a separate query.** Folding it into each write statement would save one round trip per call, at the cost of more complex SQL.
- **Triggers with empty transition tables.** These still run their upsert. An early `if not exists (select from new_rows) then return null` would save that work on toggles.

## Interpretations of the specification

The interfaces' Javadoc leaves some cases open. Each choice below keeps the result well defined and is covered by a test.

**Videos and visibility**
- A video is *published* when it has been reviewed and its public time, if it has one, is not after "now". A `NULL` public time means no embargo. Unpublished videos are visible only to their owner and superusers, in search and in like, coin and collect.
- `postVideo` and `updateVideoInfo` require a non-null public time that is not before "now". The Javadoc lists "earlier than now" as invalid and says nothing about `null`.
- `updateVideoInfo` on a video that was never reviewed applies the edit and returns `false`. The Javadoc says the result tells whether the video "needs to be re-reviewed (was reviewed before)". Any accepted edit clears the review.
- Search breaks ties in relevance by view count, as specified, and then by `bv`, so that pages are stable. Keywords are separated by spaces, and runs of spaces do not create empty keywords.

**Danmu**
- `sendDanmu` requires a published video that the user has watched, and `0 ≤ time ≤ duration`. Owners and superusers get no exception: a video without an audience has no viewers to send danmus.
- `displayDanmu` accepts `timeStart == timeEnd`. The Javadoc lists "`timeStart <= timeEnd`" as invalid, which would reject every range, so it is read as `timeStart > timeEnd`. Both ends must be within `[0, duration]`, and the video must be published.
- With `filter`, the danmu kept for each content is the earliest *posted* one within the range, ties broken by id. The result is sorted by time, then id.

**Recommendations**
- In the general score, only the like, coin and favorite rates are clamped to 1, as the Javadoc says. The danmu term (danmus per viewer) and the finish term (mean view time over duration) are not. A video nobody watched scores 0. Ties are broken by `bv`.
- `recommendVideosForUser` ranks by the number of friends who watched the video, then the owner's level (higher first), then the public time (newer first, videos without one last), then `bv`. Friends are mutual follows. With no interest at all, it returns the general recommendation; an empty later page returns an empty list.
- `recommendFriends` follows the specified order: common followings, then level descending, then `mid` ascending. Similar videos break ties by `bv`.
- Recommendations are not filtered by visibility; the Javadoc does not ask for it.

**Users**
- Empty `qq`, `wechat` and `birthday` are stored as `NULL`. An empty `sign` is kept as given.
- Sex is stored with the labels the imported data uses: 男, 女, 保密.
- A birthday is `M月D日` and must be a real calendar day. 2月29日 is accepted because a birthday has no year, and leading zeros (01月05日) are accepted.
- A regular user may delete only their own account. A superuser may also delete regular users, but not other superusers. Deleting a user removes their videos, with everything on them, and everything they did.
- `follow` returns the follow state after the call. Following yourself, or a user who does not exist, returns `false`.

**Data**
- `DatabaseService.truncate()` truncates every table, including the derived ones. `TRUNCATE` bypasses the derived-table triggers.
