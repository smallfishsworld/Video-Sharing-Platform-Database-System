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

The original implementation passes 5,618 of the 5,622 benchmark cases in each of its runs (round 1: `docs/benchmark/round1/baseline-r*.json`; round 2: `docs/benchmark/round2/plain/c2-B*.json`). The benchmark logs and the code at `2e811bd` show why. They also show problems that the benchmark does not check:

- **The four failures.** Three searches fail because `keywords.split(" ")` turns repeated spaces into empty keywords. `regexp_split_to_array(title, '')` then splits the title into single characters, so every video matches. The keywords are also used as regular expressions. The fourth failure is one view rate that is off in the fourth digit (expected 0.5450836, got 0.5451384): view times are stored as `real` and summed with `sum(real)`. The original reads `Instant.now()`. The build of it that the harness measured adds a constant offset, kept outside this repository, so it starts at the same pinned time as the current code.
- **Writes are sequences of autocommit statements.** `coinVideo` reads the balance, checks for an earlier donation, inserts the donation and then writes back a balance computed in Java. Two concurrent donations by one user can therefore spend the same coin. `follow` returns `true` even when it unfollows.
- **No foreign keys.** Deleting a video leaves its danmu likes behind. Deleting a user leaves everything that other users did on that user's videos and danmus.
- **Read paths aggregate from scratch.**
  - General recommendations hash-aggregate all five interaction tables (2.2 M rows) on every call.
  - Similar videos self-join `viewers`.
  - Friend recommendations scan all of `followings` (6.0 M rows).
- **Connections leak on errors.** `UserServiceImpl`, `DanmuServiceImpl` and `RecommenderServiceImpl` have no `finally` block and no try-with-resources. They close their connection only on the success path, so an exception leaks it. `follow`, for example, rethrows an `SQLException` without closing anything.
- **Import.** The import uses batched `INSERT`s on four threads into tables whose indexes already exist. No `ANALYZE` follows it, and it takes 57.7 s (median of the round-1 runs; 91.7 s in the interleaved round-2 runs).

## Schema

[`sql/build.sql`](../sql/build.sql), commit `66edb47`, with the derived tables added in `95a7e83`.

- **Keys in both directions.** Each relation table (`viewers`, `videolikes`, `coins`, `favorites`) has the primary key `(bv, mid)` and a secondary index `(mid, bv)`. `followings` has `(follower_mid, followee_mid)` and `(followee_mid, follower_mid)`. Every lookup is therefore a range of one B-tree, and every "count per X" can be an index-only scan. That holds from the start, because the importer leaves the visibility map set (see [Bulk import](#bulk-import)). In the "after" plans in `docs/explain`, every index-only scan but one reports `Heap Fetches: 0`. The exception reports 9, for 336 rows (`user-rec.after.txt`).
- **Foreign keys with `ON DELETE CASCADE`** on every reference except `videos.reviewer`, which is set to `NULL` when the reviewer is deleted, so the video stays. Deleting a user or a video becomes one statement, and the database, not the Java code, decides what "everything that refers to it" means. Indexes on `danmus (mid)`, `danmulikes (mid)` and `videos (reviewer)` exist only to make those cascades index lookups.
- **Types.** View times, durations and danmu times are `double precision`, which fixes the view-rate failure. `users.mid` and `danmus.id` are identity columns. `users.coin` has `CHECK (coin >= 0)`.
- **Uniqueness.** `qq` and `wechat` are unique and `NULL` when absent. `videos (owner_mid, title)` is unique, which is how "the user already has a video with this title" is enforced. A conflict makes the `INSERT` return no row, with no separate check and no race.
- **`count_occurrences(haystack, needle)`** is an immutable SQL function, `(length(h) - length(replace(h, n, ''))) / length(n)`. `replace()` removes the occurrences from left to right without overlaps. That is the counting rule of the search specification, and the Javadoc examples are unit tests (`VideoServiceTest`).

## Service layer

Commit `66edb47`. Each service in `io.sustc.service.impl` is a Spring bean with constructor injection of `NamedParameterJdbcTemplate`, `Authenticator` and `Clock`. There is no ORM and no `@Transactional`. The SQL lives in `static final` text blocks in each service, each with a comment on what it guarantees.

- **One call per write.** Coin, review, register, post, edit and delete are each one statement. The toggles (like, collect, follow, danmu like) are a per-user advisory lock and the toggle statement, sent together. The building blocks are data-modifying CTEs, `INSERT … ON CONFLICT DO NOTHING RETURNING`, and conditions in the `WHERE` clause of an `UPDATE`.
  - Atomicity comes from the statement (or, for a toggle, from the implicit transaction around its two statements), not from a transaction the caller opens.
  - Inside one statement the check is part of the act, which closes the check-then-act races of coin donation, review and insert-if-absent. It does not make a toggle safe: under READ COMMITTED a statement sees a snapshot taken when it starts, so two concurrent toggles of one row both decided on the old state and one was lost. A lock taken inside the same statement does not help, because the statement's snapshot is older than the wait. So each toggle first takes `pg_advisory_xact_lock(mid)` of the acting user in a statement of its own; the toggle statement starts after the lock is granted and sees every earlier toggle. pgjdbc sends both statements before one Sync, so they are one round trip and one implicit transaction; advisory locks write no WAL. `updateVideoInfo` reads the review state it replaces from a `FOR UPDATE` CTE, which returns the latest committed version of the row.
  - Each write is one round trip after authentication (`scripts/writebench`, count mode: see [The write path](#the-write-path)).
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
- **Authentication** (`auth/Authenticator`) is one query in the common case, and a second (`qq`, then `wechat`) when `mid` and password do not match. It returns the user's id and identity together, so the superuser check costs nothing extra. If both `qq` and `wechat` are given, they must belong to the same user. An `AuthInfo` with only a `qq` or `wechat` value authenticates without a password, so those two columns are credentials too.
- **Visibility** (`support/VideoVisibility`) is one SQL predicate shared by search, like, coin and collect. A video is *published* once it is reviewed and its public time, if any, has passed. Unpublished videos are visible only to their owner and to superusers. Danmus follow their own Javadoc: they can be sent to and displayed on published videos only, for every sender (the owner and superuser exception does not apply), and `likeDanmu` checks only that the danmu exists and that the user watched its video, without the visibility rule.
- **Time** comes from the injected `Clock` (`config/ClockConfig`). `-Dsustc.clock.start=…` starts that clock at a given instant; the benchmark data assumes 2023-12-30 12:00 +08:00.
- **Paging** (`support/Page`) validates sizes and numbers in one place and computes the offset in `long`, so large page numbers cannot overflow.
- **Logging** goes through slf4j (Lombok `@Slf4j`); there are no `System.out` calls.

## Bulk import

`importer/BulkImporter`, `DeferredSchemaObjects`, `DerivedData`, `CopyRowWriter`, `ParallelTasks`. Commit `66edb47` and later. The steps:

1. **Detach.** The importer reads the indexes, primary and unique constraints, foreign keys and `derived_*` triggers of every table from the catalog, keeps their definitions (`pg_get_indexdef`, `pg_get_constraintdef`, `pg_get_triggerdef`) together with what those functions leave out (whether a constraint is deferrable or initially deferred, whether a foreign key was validated), and drops them. No table, index or constraint name is hard-coded, so a column or index added to `build.sql` needs no importer change. Objects it cannot drop and recreate exactly are refused before anything changes: indexes behind other constraint types (exclusion constraints), and comments on indexes, constraints or `derived_*` triggers.
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

The benchmark step for the import took 8,843 ms (median of the interleaved round-2 runs; 8,259 ms in round 1). It times only `DatabaseService.importData`: the empty-table check, the catalog capture and all import phases (the importer's own "Imported … in" figure plus 20–51 ms in the round-2 runs: 8,162 → 8,184, 8,792 → 8,843 and 8,923 → 8,943 ms in `round2/plain/c2-O*.log`). The benchmark deserializes the `.ser` files before it starts its clock (`BenchmarkService.java:64-68`), for the baseline and this version alike.

**Failure handling.**
- If any phase fails, the importer truncates every imported table and restores whatever schema objects are missing, then throws an `ImportException` that names the phase. The imported tables are left empty with their complete schema. The `users` audit log is not emptied: it keeps the failed attempt's `INSERT` records and the `TRUNCATE` records. A non-empty database is refused with an `IllegalStateException` before anything changes.
- `BulkImporterTest` injects a foreign-key violation, a duplicate key and a failure during the load, then compares the catalog with a snapshot taken before the import and checks the audit trail. Other tests cover deferrable and `NOT VALID` constraints, a dropped identity column, refused schema objects, and both branches of the co-view computation (`DerivedDataTest`, against brute force).
- This test found a bug, now fixed. After a failed validation, the foreign keys that had been added `NOT VALID` stayed unvalidated: 8 of them, in the run that exposed it. `restoreMissing` now validates the captured foreign keys that are still marked `NOT VALID` (`DeferredSchemaObjects.NOT_VALIDATED_FOREIGN_KEYS`), except those that were `NOT VALID` before the import, and recreates the triggers even if a validation fails.

Imported row counts: users 37,881; videos 44; followings 5,958,770; viewers 874,856; videolikes 480,154; coins 447,153; favorites 430,622; danmus 322; danmulikes 11,068; video_stats 44; video_coviews 1,892 (the `Imported …` line of every round-2 log, for example [benchmark/round2/plain/c2-O1.log](benchmark/round2/plain/c2-O1.log)).

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
- **One sorted upsert per change.** Counters move by signed deltas in an `INSERT … ON CONFLICT DO UPDATE`, ordered by `bv`, so within one trigger the counter rows are locked in `bv` order. That order holds per trigger, not per statement: deleting an account cascades to five tables, each cascade fires its own statement trigger, and each trigger takes its own sorted pass over `video_stats`. Two account deletions with crossing interactions can therefore lock the same counters in opposite orders and deadlock; `ConcurrencyTest.crossingAccountDeletionsBothSucceed` produces that deadlock on every run. The services retry a call that loses a deadlock (`support/Writes`, up to 3 attempts; inside a caller's transaction the deadlock aborts the transaction and the caller retries). That is enough for two crossing deletions, not for eight at once (see [The write path](#the-write-path)). Rows of videos that are being deleted are skipped; their counters go with them.
- **No `CHECK (>= 0)` on the counters.** PostgreSQL checks the row an `INSERT … ON CONFLICT` proposes before it finds the conflict, so a negative delta would fail even though it only ever becomes an update. The tests compare the counters with a full recomputation instead.
- **Co-views.**
  - When a user's views of a set of videos appear, every pair between those videos and the user's other videos gains one. Pairs within the set gain one too.
  - When the views disappear, the same pairs lose one. Pairs that reach zero are deleted, so `video_coviews` has no zero rows (`CHECK (viewers > 0)`).
  - Updating the `(bv, mid)` key of a view is rejected: a different key is a different viewer.
  - The pairs are computed from the user's other views, so two transactions adding views for one user at once each missed the other's view and lost a pair. `derived_viewers` therefore first locks the affected `users` rows (`FOR NO KEY UPDATE`, in `mid` order) in a statement of its own; a user's views are maintained one transaction at a time.
  - **Size and fan-out.** `video_coviews` holds at most N(N−1) rows for N videos; here it is complete, 44 · 43 = 1,892 rows. A new view by a user who has watched k videos changes 2(k−1) rows, and deleting a user who watched k videos changes k(k−1) rows (here the median user has watched 23 videos, 506 rows, and the heaviest 36, 1,260 rows). Both grow with the square of a user's view count. Deleting an account takes 13.8–15.3 µs of server time per co-view row it changes (least squares over 39 accounts, see [The write path](#the-write-path)); a larger catalogue would store unordered pairs or cap the pairs per user.
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

### Hotspots and danmu display

These two steps are cheap reads (56 and 382 calls) that the benchmark times on their first pass, and they are the only reads that are slower than in the original (14 → 19 ms and 72 → 80 ms by the medians). WriteBench's reads mode replays both steps' cases in a fresh JVM right after the import, five passes in a row, each pass on a parallel stream as the benchmark runs it (3 runs per version, alternating; [benchmark/round2/writebench/summary.md](benchmark/round2/writebench/summary.md)):

| Pass | Hotspots (56 cases), original | this version | Danmu display (382 cases), original | this version |
|---:|---:|---:|---:|---:|
| 1 | 36.2 [33.2–38.5] | 30.3 [24.7–31.5] | 71.6 [62.2–105.7] | 114.6 [107.1–123.6] |
| 2 | 13.7 [11.5–15.1] | 11.2 [11.1–13.1] | 50.6 [34.1–55.5] | 74.6 [70.7–79.0] |
| 3 | 13.9 [10.9–14.2] | 11.6 [11.4–13.8] | 38.3 [34.0–53.9] | 58.3 [48.5–59.6] |
| 4 | 8.7 [8.2–11.2] | 11.2 [9.6–11.2] | 34.9 [34.7–40.9] | 36.2 [30.3–59.9] |
| 5 | 8.8 [8.2–12.2] | 12.3 [9.8–12.3] | 29.5 [28.6–38.9] | 34.5 [29.4–41.7] |

Milliseconds per pass, median of the 3 runs [smallest–largest]; every answer was correct in every pass. The cost is in the first passes: from the fourth pass on the two versions are within each other's ranges. Hotspots are not slower here even on the first pass (30.3 against 36.2 ms); in the benchmark the step comes after the import and steps 2–3, which run far longer in the original through the same JDBC path, and whether that warm-up explains its 14 → 19 ms was not measured. Danmu display is slower here for the first three passes (114.6 against 71.6 ms on the first). On the server, a first execution costs little more than later ones: in a fresh `psql` session the hotspot query took 0.181 ms to plan and 0.176 ms to run the first time and 0.061–0.080 + 0.039–0.069 ms the next two times, the display query 0.143 + 0.062 ms and then 0.071–0.104 + 0.037–0.041 ms ([explain/first-call.after.txt](explain/first-call.after.txt), `docs/explain/first-call.sql`, EXPLAIN (ANALYZE, TIMING OFF) on the video with the most danmus). If each pooled connection pays what that fresh session paid, first executions add up to 10 × 0.26 ms to a hotspot pass and 10 × 0.09 ms to a display pass (the pool has at most 10 connections: Hikari's default, which neither the runner nor WriteBench changes). Where the rest of the 43 ms of the first display pass goes was not measured; JIT compilation of the new code path is a candidate.

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

The benchmark's 11 write steps take 2,846 ms in the original and 3,353 ms in this version (sums of the step medians of the plain runs). Danmu send (+165 ms), like (+95), collect (+82) and delete video (+141) make up most of the difference; delete account is 45 ms faster, and the other six together are 69 ms slower. Round 1 explained the difference with the timing after the import. Round 2 measured that explanation first, then the work per call.

**The timing after the import** ([benchmark/round2/plain/report.txt](benchmark/round2/plain/report.txt), [settle/report.txt](benchmark/round2/settle/report.txt); `scripts/bench_report.py`):

- Writeback is not the cause. At the start of step 10 the kernel's dirty memory (`Dirty` in `/proc/meminfo`) is 1.1–2.0 MB in this version's runs and 63–90 MB in the original's.
- A checkpoint is in progress during the write steps of every run of both versions. WAL-triggered checkpoints follow each other through both imports; the last one started 2–3 s before step 10 here and 24–29 s before it in the original.
- The settle variant runs a `CHECKPOINT` after the import and waits until dirty memory is below 64 MB. There the write steps took 3,189 ms here and 2,892 ms in the original, against 3,353 and 2,846 ms in the plain runs. The per-run sums overlap (this version: 3,194–3,507 ms plain, 3,041–3,267 ms settle), so the checkpoint costs this version about 5 %, which three runs per variant cannot separate from noise, and the original nothing measurable. Most of those 5 % are in danmu send (369 → 291 ms) and delete video (591 → 526 ms).
- Full-page images are written in both variants and by both versions: the first change of a page after a checkpoint has started copies the page into the WAL, whether or not the checkpoint has finished. The write steps write 112,797 kB of WAL in the original and 121,260 kB here (medians), 79,129 and 86,079 kB of it page images; per step, coin, like, collect and the two deletes are within 25 % of each other.
- The exception is danmu send: 2,307 kB instead of 112 kB, 2,052 kB of it page images. The foreign key `danmus.mid → users` checks the sender with a `FOR KEY SHARE` lock on its `users` row; the lock is a `Heap/LOCK` WAL record and, for the first lock on a page after the checkpoint, comes with an image of that page. Measured on its own ([experiments/fk-lock-wal.txt](experiments/fk-lock-wal.txt), `docs/experiments/fk-lock-wal.sh`, `pg_waldump --stats=record`): 200 danmus from 200 different users, inserted right after a `CHECKPOINT` in a transaction that is rolled back, wrote 1,514,237 bytes of WAL, 1,418,380 bytes of it page images attached to the 202 `Heap/LOCK` records; the same 200 danmus inserted again right afterwards wrote 77,487 bytes, with the same 202 lock records and no page images. Follow (297 → 619 kB) has two such foreign keys into `users`; that the cause is the same there was not measured.

**The work per call.** WriteBench ([benchmark/round2/writebench/summary.md](benchmark/round2/writebench/summary.md), `scripts/writebench/tables.py`) runs the benchmark's write operations through the service layer of either jar, on the benchmark data as that jar imports it, after a `CHECKPOINT` and 300 warm-up calls: 600 calls per operation (8 deletions of imported videos), 5 runs per version and client count, alternating, each under the bench lock. Medians of the per-run p50 (ms), original → this version, and the statements and round trips per call from its count mode:

| Operation | 1 client | 8 clients | Statements / round trips per call |
|---|---:|---:|---:|
| coin | 1.32 → 0.92 | 2.13 → 2.21 | 7 / 7 → 2 / 2 |
| like video | 0.71 → 0.84 | 1.35 → 2.09 | 5 / 5 → 3 / 2 |
| collect | 0.73 → 0.84 | 1.31 → 2.04 | 5 / 5 → 3 / 2 |
| danmu send | 0.76 → 0.83 | 1.71 → 1.94 | 5.05 / 5.05 → 2 / 2 |
| danmu like | 0.76 → 0.60 | 1.41 → 1.43 | 5 / 5 → 3 / 2 |
| post video | 0.55 → 0.76 | 1.16 → 1.64 | 4 / 4 → 2 / 2 |
| register | 0.45 → 0.45 | 0.97 → 0.82 | 2 / 2 → 1 / 1 |
| update video | 0.56 → 0.97 | 1.10 → 1.09 | 4 / 4 → 2 / 2 |
| review | 0.43 → 0.44 | 0.94 → 0.95 | 3 / 3 → 2 / 2 |
| delete video, no views | 1.15 → 1.10 | 2.47 → 1.83 | 9 / 9 → 2 / 2 |
| delete video, ≈ 20,000 views | 27.45 → 49.53 | 121 → 12,202 | 9 / 9 → 2 / 2 |
| delete account | 5.38 → 6.57 | 24.80 → 18,025 | 13 / 13 → 2 / 2 |
| follow | 0.73 → 0.71 | 1.17 → 1.51 | 4 / 4 → 3 / 2 |

8 clients: in this version's runs 4–6 of the 8 deletions of imported videos and 79–83 of the 100 account deletions failed with `deadlock detected` after 3 attempts, none in the original's; account deletions on 8 clients are runs of their own (3 per version, 100 calls each). This version's toggles send the lock statement and the toggle in one round trip.

- By the medians, coin donation (1.32 → 0.92 ms) and danmu like (0.76 → 0.60 ms) are faster per call at steady state; register, review, deleting a video without views and follow cost the same; like, collect and danmu send are 0.07–0.13 ms slower, post video 0.21 ms and update video 0.41 ms.
- The deletes that cascade are slower: deleting an imported video (about 20,000 views) takes 49.53 ms instead of 27.45 ms, deleting an account 6.57 ms instead of 5.38 ms. On the server, by EXPLAIN ANALYZE in rolled-back transactions on the data as this version imports it ([explain/delete-costs.after.txt](explain/delete-costs.after.txt), `docs/explain/delete-costs.sql`, trigger times summed per execution by `docs/explain/trigger-times.awk`; executions 2–5 of 5 in one session): deleting a video with 18,179 views, 10,429 likes, 10,129 coins and 10,064 favorites takes 28.9–30.7 ms, 23.9–25.9 ms of it in the foreign-key cascades and 4.2–4.5 ms in the derived-table trigger functions (the session's first execution: 38.7, 29.6 and 8.5 ms). Collecting the deleted rows into the triggers' transition tables happens inside the cascades and was not separated. Deleting an account with 23 views and 177 follow rows takes 11.7–15.1 ms, 10.0–13.2 ms of it in the derived-table triggers and 9.5–12.4 ms of that in the one on `viewers`, which updates the view counters and the 506 (23 × 22) co-view rows of those views; the cascades take 1.3–1.7 ms. Timed without EXPLAIN over 39 accounts that own no video (every 1,000th by mid, and that one; [explain/delete-account-sample.after.txt](explain/delete-account-sample.after.txt)), the server-side delete takes a median of 10.7 ms in a first pass and 10.0 ms in a second (6.1–18.3 and 4.9–17.0 ms), and it grows with the co-view rows it changes: 13.8 and 15.3 µs per row by least squares, correlation 0.79 and 0.92. WriteBench's p50 (6.57 ms, commit included) is lower than that median. Its account deletions run after its 8 deletions of imported videos, which take those videos' views with them, so its accounts have fewer views left; how many fewer, and whether that explains the whole difference, was not measured.
- The settle runs of the benchmark show the same split: coin, register, update video, review and delete account are not slower than the original there, while danmu send (+90 ms), like (+146), collect (+64), post video (+8), delete video (+80) and follow (+8) are.
- Coin is the exception between the two measurements: one statement instead of seven, 30 % faster per call in WriteBench, but not faster in the benchmark (547 → 567 ms plain, 556 → 515 ms settle, ranges overlapping). Both versions write about 7 MB of page images in that step; why the per-call gain does not show there was not measured.

**Triggers and foreign keys of one like** (pgbench, `scripts/write_latency.sh`, [experiments/write-latency.txt](experiments/write-latency.txt), one client, 1,000 transactions per measurement): with the derived-table triggers and the foreign keys 1.019 ms [0.935–1.064], without the triggers 0.887 ms [0.839–0.924], without the triggers and the foreign keys 0.794 ms [0.657–0.852] (median [range] of 5 alternating rounds). Without the triggers a like takes about 0.13 ms less, and the ranges with and without them do not overlap. Without the foreign keys as well it takes another 0.09 ms less, but those two ranges overlap (0.839–0.924 and 0.657–0.852 ms), so that difference is not separated from the noise. Right after the import and 120 s later the like took 0.957 and 0.994 ms (dirty memory 932 kB and 140 kB), so round 1's 1.30 → 0.78 ms, measured with 143 MB of dirty data after the import, did not recur. Part 3 of the script (the original's statement sequence in pgbench) needs the schema `original` and was skipped; WriteBench measures the original's own code instead.

**One coin donation, with EXPLAIN** ([explain/coin-donation.after.txt](explain/coin-donation.after.txt), `docs/explain/coin-donation.sql`, rolled back): user 51, who had 91 coins and had not donated to BV1PK4y1b7dt, donates a coin to it five times in one session, each time rolled back. The donation happens each time: the update of `users` returns its row (rows=1), the insert's input has one row, and the two foreign-key checks on `coins`, which fire once per inserted row, run once each (the `Insert on coins` node itself shows rows=0 because the statement returns nothing). The session's first execution takes 2.670 ms (planning 0.296 ms), most of it in the audit trigger (1.145 ms) and the counter trigger (0.750 ms), which run for the first time in the session there. The four later ones take 0.317–0.498 ms (planning 0.133–0.163 ms), of which the counter trigger takes 0.089–0.135 ms, the audit trigger 0.049–0.108 ms and the two foreign-key checks 0.047–0.075 ms together. Round 1's EXPLAIN of a coin donation measured one that inserted nothing; these numbers replace it.

**Under concurrency** (WriteBench, 8 clients): like, collect, post video and follow reach 24–35 % less throughput than in the original; danmu send 16 % more, register 24 % more and deleting a video without views 58 % more; coin, danmu like, update video and review are within each other's run ranges. Why was not measured; a candidate for like and collect is the video's counter row in `video_stats`, which every like of that video updates and keeps locked until commit. Deletions that cascade deadlock with each other: in each of the 5 runs, 4–6 of the 8 deletions of imported videos failed with `deadlock detected` after 3 attempts (15–20 deadlocks per run), and in each of 3 runs of 100 account deletions on 8 clients 79–83 failed (310–316 deadlocks per run, p50 18 s); the original completed all of them (p50 121 ms and 24.8 ms). The retry in `support/Writes` (up to 3 attempts) is enough for two crossing deletions (45 trials of two crossing account deletions 0–8 ms apart: 4 failed with `deadlock detected` on the round-1 code and none on this version, where 2 deadlocks were detected and retried) but not for eight; see [Open questions](#open-questions).

## Planner statistics and the friend step

In round 1 the baseline's friend step took 1,932, 8,910 and 9,008 ms. The explanation offered then was that the original importer never runs `ANALYZE`, so the plan depended on whether autovacuum had analyzed `followings` by step 8. Round 2 tested it ([benchmark/round2](benchmark/round2), `scripts/bench_report.py`; `bench_instrumented.py` records `pg_stats` and `pg_stat_user_tables` for `followings` and the plan of the step's median query, user 18398, when step 7 ends):

| Original implementation | Step 8 | Statistics of `followings` at step 8 | Plan |
|---|---:|---|---|
| as it is (plain runs `c2-B1..3`) | 11,970 / 12,768 / 12,928 ms | from autovacuum during the import: one or two autoanalyze runs, 0.9–2.5 M rows changed since; `n_distinct(mid1)` 9,768–13,051 | hash anti-join over a hash join |
| `VACUUM ANALYZE` right after the import (`c4-B1..3`) | 11,671 / 11,440 / 12,183 ms | analyzed in the pause; `n_distinct(mid1)` 14,590–14,673 | the same |

- With fresh statistics the original is not faster, and the plan is the same. So missing statistics do not explain the original's friend step in round 2, and the 1,932 ms run of round 1 remains unexplained.
- Round 1's `EXPLAIN` without statistics (`friend-rec.before-nostats.txt`, 608 and 663 ms against 331 and 441 ms) is a per-query difference of 1.5–1.8× on another day; it does not carry over to the step.
- This version's importer ends with `VACUUM (ANALYZE)` (`n_mod_since_analyze` is 0 at step 8). Its friend step took 519, 686 and 708 ms, 18.6× faster than the original's median; the covering index `followings (followee_mid, follower_mid)` and the rewritten query are what changed.

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

- **m1 core.** The rewritten services, the new schema and the `COPY` importer with `ANALYZE`. All cases pass, the import is 5× faster, and the friend step takes 708 ms. Similar videos and the recommendations still aggregate from scratch.
- **m2 derived.** The derived tables and their triggers. Similar videos, general and user recommendations, and search drop to tens of milliseconds. Deletes get slower, because they now maintain counters.
- **m3 upsert.** The counter triggers became one sorted upsert each, and the counter `CHECK`s were dropped.
- **m4 friends.** The friend query applies the anti-join after the aggregation, which was the cheapest of the variants compared over all 133 valid calls. The viewers delete trigger became a single pass.

`docs/benchmark/round1/dev/script-check.json` is one run of `scripts/benchmark.sh` on the final code. It shows that the script in this repository reproduces the procedure: 5,622 / 5,622 passed, an import of 8,471 ms, and 3,899 ms for steps 2–23.

Command: `cd /home/user/work/bench6 && python3 -I compare.py m1=m1_core.json m2=m2_derived.json m3=m3_upsert.json m4=m4_friends.json` (the same files as in `docs/benchmark/round1/dev`).

## Tests

There are 142 tests (JUnit 5, counted as JUnit reports them: each argument set of a parameterized test is one test); 108 of them are integration tests against PostgreSQL 16 in Testcontainers, the other 34 are unit tests. `./gradlew build` runs them, plus `BulkImporterTest` a second time with the JVM in the benchmark's time zone (Asia/Shanghai, task `importerTestInBenchmarkZone`): 151 test executions. The CI workflow (`.github/workflows/ci.yml`) runs the same build on JDK 17 and 21, but it has not run on GitHub, because nothing has been pushed. Its build command was run locally in a fresh clone (`git clone --no-local`, `core.fileMode` true, `gradlew` and the scripts committed as mode 100755): it passes on JDK 21 and, in an `eclipse-temurin:17-jdk` container, on JDK 17 ([experiments/ci-proof.txt](experiments/ci-proof.txt), [ci-proof-jdk21.log](experiments/ci-proof-jdk21.log), [ci-proof-jdk17.log](experiments/ci-proof-jdk17.log); `--offline --max-workers=2` added because the machine is shared, and `--no-build-cache` so that every task runs although `gradle.properties` enables Gradle's local build cache).

**Setup.**
- Integration tests extend `IntegrationTest`. It starts one `postgres:16-alpine` container for the whole run (Testcontainers 1.21.4) and wires it in with `@DynamicPropertySource`.
- The container is initialized with locale C, like the benchmark database, and runs with `fsync`, `synchronous_commit` and `full_page_writes` off, which is safe for a disposable test database. `CollationIndependenceTest` builds the schema in a second database of the same container whose default collation is ICU en-US.
- The schema is created once from `sql/`, and every test starts from truncated tables.
- After each test, `noConnectionIsLeaked()` asserts that the Hikari pool has no active connection.

| Class | Tests | What it covers |
|---|---:|---|
| `UserServiceTest` | 28 | registration rules, authentication, deletion rights and cascades, follow state, user info |
| `VideoServiceTest` | 23 | post/update/review/delete, visibility, search relevance (the Javadoc examples, non-ASCII case folding, pattern characters), view rate, coin/like/collect, concurrent coin donations |
| `DanmuServiceTest` | 10 | send rules, display ranges, the earliest-posted filter and its tie-break, danmu likes |
| `RecommenderServiceTest` | 10 | similar videos with ties, general score with clamping, unreviewed videos, friends' videos, friend recommendations and their tie-breaks |
| `ConcurrencyTest` | 11 | concurrent toggles, edits and co-view updates (lost updates), crossing account deletions (a deadlock on every run, retried), a video and its liker deleted at once |
| `TransactionCompositionTest` | 4 | refused writes inside a caller's transaction leave it usable |
| `CollationIndependenceTest` | 5 | bv tie-breaks in byte order in a database whose default collation is ICU |
| `DerivedDataConsistencyTest` | 5 | randomized workloads (seeds 1, 20231230, 424242; 400 steps each; 18 kinds of service call or multi-row SQL). Every 50 steps, `video_stats` and `video_coviews` must equal a recomputation from scratch, and each kind of action must have changed something |
| `BulkImporterTest` | 9 | stored data equals the input, sequences continue, the catalog is unchanged, statistics exist, refusals, and failures (load, indexes, constraints) leave empty tables with the complete schema and an audit trail |
| `DerivedDataTest` | 3 | the dense and the sparse co-view computation against brute force |
| `DatabaseServiceTest` | 3 | truncate (the audit log keeps one `TRUNCATE` record), group members, the sum helper |
| `WritesTest` | 5 | deadlock retries, SQLSTATE mapping |
| `ServiceHelpersTest`, `ClockConfigTest`, `PageTest` | 26 | keyword splitting, birthdays, sex labels, the pinned clock, paging arithmetic |

**Do the concurrency tests test anything?** `ConcurrencyTest`, `TransactionCompositionTest` and `CollationIndependenceTest` were run against the code before the fixes (commit `978fe40` with the new tests copied in; `TransactionCompositionTest` without its one test of `support/Writes`, which did not exist yet) and against this version ([experiments/concurrency-tests-old-vs-new.txt](experiments/concurrency-tests-old-vs-new.txt)). On the old code 15 of the 19 fail: 10 of the 11 in `ConcurrencyTest` (all but `deletingAVideoAndOneOfItsLikersAtOnceBothSucceed`, which passes there too), 4 of the 5 in `CollationIndependenceTest` (all but `theDatabaseReallySortsCaseFirstByLanguage`, which checks the test database itself) and 1 of the 3 in `TransactionCompositionTest` (`aDuplicateTitleInAnUpdateLeavesTheTransactionUsable`; the post and donation cases already passed). On this version all 20 pass.

## Tried and rejected

- **Dropping the foreign keys for write speed.** In pgbench a like without the derived-table triggers took 0.887 ms with the foreign keys and 0.794 ms without them (medians of 5 alternating rounds whose ranges overlap; [experiments/write-latency.txt](experiments/write-latency.txt)), a difference the measurement cannot separate from noise. Without the foreign keys, the cascades would have to be written by hand again.
- **`CHECK (>= 0)` on the counters.** These are incompatible with signed-delta upserts; see [Derived tables](#derived-tables).
- **Rebuilding `video_coviews` in SQL after the load.** That needs a self-join of all 875 k views on the user. `DerivedData` instead counts the pairs in Java, during the load phase, while the records are in memory.
- **An early return in the derived-table triggers.** A toggle fires both its insert and its delete trigger, and one of the two has an empty transition table; the deletes run the co-view branch for videos that are being deleted anyway. A variant of `sql/build.sql` that returns early in both cases ([benchmark/round2/b1/build-early-return.sql](benchmark/round2/b1/build-early-return.sql)) was measured with WriteBench against the current triggers: 5 alternating rounds, 1 client, after a checkpoint, 300 warm-up and 600 measured calls per operation. Median p50 per call, current vs early return: sendDanmu 0.976 vs 1.125 ms, coinVideo 1.076 vs 1.078, likeVideo 0.988 vs 1.050, collectVideo 0.962 vs 0.873, deleting a video with views 35.6 vs 33.7, deleteAccount 6.75 vs 6.57 ms; every range of one variant overlaps the other's ([benchmark/round2/b1](benchmark/round2/b1), `scripts/writebench/summarize.py`). No gain that the measurement can separate from noise, so it was not kept.
- **Not tried: server settings.** Parallel query, JIT and the checkpoint settings stay as the shared server has them for every run.

## Open questions

- **Concurrent cascading deletes.** On 8 clients, deletions of imported videos and of accounts deadlock with each other more often than 3 attempts absorb (see [The write path](#the-write-path)). Two changes would remove those cycles, and neither has been measured: serialize the deletes that change derived rows (a transaction-scoped advisory lock in a key space of its own, taken as their first statement), or lock the affected `video_stats` and `video_coviews` rows in one global order before deleting. Storing each co-view pair once (`bv < other_bv`) would remove the video–video cycle and halve the co-view rows a view changes.
- **The co-view delete branch.** Deleting an account spends most of its server time in `derived_viewers` (9.5–12.4 of 11.7–15.1 ms for an account with 23 views, see [The write path](#the-write-path)), which locks the affected co-view rows, deletes those that reach zero and updates the rest, in three statements. One sorted decrement could replace them; it needs `CHECK (viewers > 0)` dropped (an upsert checks the proposed row first), a cleanup of zero rows, and counts that are exact. Not tried: this round kept the code of the benchmarked jar.
- **Writes under concurrency.** On 8 clients like and collect reach about a third less throughput than the original. Whether the counter rows in `video_stats` are the cause, and whether batching the counter updates per transaction would help, is open.
- **Coin donation in the benchmark.** One statement and faster per call at steady state, but not faster in the benchmark step.
- **Authentication is a separate query.** Folding it into each write statement would save one round trip per call (every write except register takes two round trips today, the authentication query and the write; WriteBench count mode), at the cost of more complex SQL.
- **Writes right after the import.** A `CHECKPOINT` at the end of the import would take the checkpoint out of the write steps (about 5 % of them, see above). It needs a superuser or `pg_checkpoint` membership, which the `sustc` role lacks.

## Interpretations of the specification

The interfaces' Javadoc leaves some cases open. Each choice below keeps the result well defined. The service test classes cover them; the four that the round-1 review found untested (unreviewed videos in recommendations, an empty sign, `likeDanmu` on an unpublished video, the filter's tie-break by id) now have tests too.

**Videos and visibility**
- A video is *published* when it has been reviewed and its public time, if it has one, is not after "now". A `NULL` public time means no embargo. Unpublished videos are visible only to their owner and superusers, in search and in like, coin and collect.
- `postVideo` and `updateVideoInfo` require a non-null public time that is not before "now". The Javadoc lists "earlier than now" as invalid and says nothing about `null`.
- `updateVideoInfo` on a video that was never reviewed applies the edit and returns `false`. The Javadoc says the result tells whether the video "needs to be re-reviewed (was reviewed before)". Any accepted edit clears the review.
- Search breaks ties in relevance by view count, as specified, and then by `bv`, so that pages are stable. Keywords are separated by spaces, and runs of spaces do not create empty keywords.

**Danmu**
- `sendDanmu` requires a published video that the user has watched, and `0 ≤ time ≤ duration`. Owners and superusers get no exception: the Javadoc rejects unpublished videos for every sender, so the owner and superuser exception of the visibility rule does not apply.
- `likeDanmu` checks only that the danmu exists and that the user watched its video, as its Javadoc says; it does not apply the visibility rule.
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
