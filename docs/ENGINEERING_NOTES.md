# Engineering notes

This file covers how the 2023 course implementation (commit `2e811bd`, the 2023 course submission on `main`) became the current one: what changed, why, what was measured, and how the specification was read where it is ambiguous. The [README](../README.md) has the overview and the benchmark table; this file has the detail behind them. "Round 1", "round 2" and "round 3" are the three measured iterations of the 2026 rebuild (`978fe40`, `a330c0c` and `ac3a0dc`), and round 4 the WriteBench re-runs on the final code, as defined in the README's [methodology](../README.md#methodology).

All timings come from one machine: 4 vCPUs, 15 GiB of RAM, and PostgreSQL 16 with `shared_buffers` 1 GB, `synchronous_commit` on and JIT on. Each run held an exclusive lock, so no other benchmark overlapped it. Every number below names the file it comes from.

## Contents

1. [Starting point](#starting-point)
2. [Schema](#schema)
3. [Service layer](#service-layer)
4. [Bulk import](#bulk-import)
5. [Derived tables](#derived-tables)
6. [Query plans](#query-plans)
7. [The write path](#the-write-path)
8. [Planner statistics and the friend step](#planner-statistics-and-the-friend-step)
9. [Development checkpoints](#development-checkpoints)
10. [Tests](#tests)
11. [Tried and rejected](#tried-and-rejected)
12. [Open questions](#open-questions)
13. [Interpretations of the specification](#interpretations-of-the-specification)

## Starting point

The 2023 implementation passes 5,618 of the 5,622 benchmark cases in each of its runs (round 1: `docs/benchmark/round1/baseline-r*.json`; round 2: `docs/benchmark/round2/plain/c2-B*.json`; round 3: `docs/benchmark/round3/plain/c6-B*.json`). The benchmark logs and the code at `2e811bd` show why. They also show problems that the benchmark does not check:

- **The four failures.** Three searches fail because `keywords.split(" ")` turns repeated spaces into empty keywords. `regexp_split_to_array(title, '')` then splits the title into single characters, so every video matches. The keywords are also used as regular expressions. The fourth failure is one view rate that is off in the fourth digit (expected 0.5450836, got 0.5451384): view times are stored as `real` and summed with `sum(real)`. The 2023 code reads `Instant.now()`. The build of it that the harness measured adds a constant offset, kept outside this repository, so it starts at the same pinned time as the current code.
- **Writes are sequences of autocommit statements.** `coinVideo` reads the balance, checks for an earlier donation, inserts the donation and then writes back a balance computed in Java. Two concurrent donations by one user can therefore spend the same coin. `follow` returns `true` even when it unfollows.
- **No foreign keys.** Deleting a video leaves its danmu likes behind. Deleting a user leaves everything that other users did on that user's videos and danmus.
- **Read paths aggregate from scratch.**
  - General recommendations hash-aggregate all five interaction tables (2.2 M rows) on every call.
  - Similar videos self-join `viewers`.
  - Friend recommendations scan all of `followings` (6.0 M rows).
- **Connections leak on errors.** `UserServiceImpl`, `DanmuServiceImpl` and `RecommenderServiceImpl` have no `finally` block and no try-with-resources. They close their connection only on the success path, so an exception leaks it. `follow`, for example, rethrows an `SQLException` without closing anything.
- **Import.** The import uses batched `INSERT`s on four worker threads plus the main thread (five connections) into tables whose indexes already exist. No `ANALYZE` follows it. It took 57.7 s in round 1 (median, not interleaved), 91.7 s in the interleaved round-2 runs and 103.4 s in round 3's; why the same jar drifted was not measured.

## Schema

[`sql/build.sql`](../sql/build.sql), commit `66edb47`, with the derived tables added in `95a7e83`.

- **Keys in both directions.** Each relation table (`viewers`, `videolikes`, `coins`, `favorites`) has the primary key `(bv, mid)` and a secondary index `(mid, bv)`. `followings` has `(follower_mid, followee_mid)` and `(followee_mid, follower_mid)`. Every lookup is therefore a range of one B-tree, and every "count per X" can be an index-only scan. That holds from the start, because the importer leaves the visibility map set (see [Bulk import](#bulk-import)). In the "after" plans in `docs/explain`, every index-only scan but one reports `Heap Fetches: 0`. The exception reports 9, for 336 rows (`user-rec.after.txt`).
- **Foreign keys with `ON DELETE CASCADE`** on every reference except `videos.reviewer`, which is set to `NULL` when the reviewer is deleted, so the video stays, and the derived table `video_coviews`, which since round 3 has no foreign keys (a trigger deletes its rows, see [Derived tables](#derived-tables)). Deleting a user or a video becomes one statement, and the database, not the Java code, decides what "everything that refers to it" means. Indexes on `danmus (mid)`, `danmulikes (mid)` and `videos (reviewer)` exist only to make those cascades index lookups.
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
- **Deletions take the deletion lock first** (round 3: `c10ffd0`, `1717da9`; `support/Writes`). A deletion cascades table by table, and the derived-table trigger of each cascaded table locks the counters of its own videos, so two deletions could lock shared counter rows in crossing orders (see [Derived tables](#derived-tables)). The first statement of `deleteAccount` is therefore `pg_advisory_xact_lock(1, 0)` (a two-key advisory lock, a key space separate from the one-key locks of the toggles) and the first statement of `deleteVideo` is `pg_advisory_xact_lock_shared(1, 0)`; each is sent with its `DELETE` in one round trip, like the toggles. Account deletions run one at a time and never beside a video deletion; video deletions run beside each other, because they change no counter of another video and the co-view rows they share are deleted in key order. An account with no views, likes, coins, favorites, danmus or videos changes no derived row, so its deletion skips the lock (the lock statement's `WHERE EXISTS …` filter); the benchmark deletes its freshly registered users on a parallel stream, and those stay concurrent. An interaction committed by such a user between that check and the cascade is deleted without the lock, and the deadlock retry covers that window.
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

**Phase times** of the round-3 series (`Import phase …` lines in [benchmark/round3/plain/c6-O1.log](benchmark/round3/plain/c6-O1.log), `c6-O2.log` and `c6-O3.log`):

| Phase | O1 | O2 | O3 |
|---|---:|---:|---:|
| load | 2,759 ms | 2,866 ms | 2,654 ms |
| indexes | 5,179 ms | 5,071 ms | 4,746 ms |
| constraints | 1,351 ms | 1,532 ms | 1,545 ms |
| vacuum analyze | 250 ms | 222 ms | 207 ms |

The benchmark step for the import took 9,648 ms (median of the interleaved round-3 runs; 8,843 ms in round 2 and 8,259 ms in round 1). It times only `DatabaseService.importData`: the empty-table check, the catalog capture and all import phases (the importer's own "Imported … in" figure plus 16–46 ms in the round-3 runs: 9,319 → 9,335, 9,621 → 9,648 and 9,770 → 9,816 ms in `round3/plain/c6-O*.log`; 20–51 ms in round 2's). The benchmark deserializes the `.ser` files before it starts its clock (`BenchmarkService.java:64-68`), for the baseline and this version alike.

**Failure handling.**
- If any phase fails, the importer truncates every imported table and restores whatever schema objects are missing, then throws an `ImportException` that names the phase. The imported tables are left empty with their complete schema. The `users` audit log is not emptied: it keeps the failed attempt's `INSERT` records and the `TRUNCATE` records. A non-empty database is refused with an `IllegalStateException` before anything changes.
- `BulkImporterTest` injects a foreign-key violation, a duplicate key and a failure during the load, then compares the catalog with a snapshot taken before the import and checks the audit trail. Other tests cover deferrable and `NOT VALID` constraints, a dropped identity column, refused schema objects, and both branches of the co-view computation (`DerivedDataTest`, against brute force).
- This test found a bug, now fixed. After a failed validation, the foreign keys that had been added `NOT VALID` stayed unvalidated: 8 of them, in the run that exposed it. `restoreMissing` now validates the captured foreign keys that are still marked `NOT VALID` (`DeferredSchemaObjects.NOT_VALIDATED_FOREIGN_KEYS`), except those that were `NOT VALID` before the import, and recreates the triggers even if a validation fails.

Imported row counts: users 37,881; videos 44; followings 5,958,770; viewers 874,856; videolikes 480,154; coins 447,153; favorites 430,622; danmus 322; danmulikes 11,068; video_stats 44; video_coviews 1,892 (the `Imported …` line of every round-2 and round-3 log, for example [benchmark/round3/plain/c6-O1.log](benchmark/round3/plain/c6-O1.log)).

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
- **One sorted upsert per change.** Counters move by signed deltas in an `INSERT … ON CONFLICT DO UPDATE`, ordered by `bv`, so within one trigger the counter rows are locked in `bv` order. That order holds per trigger, not per statement: deleting an account cascades to five tables, each cascade fires its own statement trigger, and each trigger takes its own sorted pass over `video_stats`. Two account deletions with crossing interactions can therefore lock the same counters in opposite orders and deadlock. Until round 2 the services only retried such a call (`support/Writes`, up to 3 attempts), which was enough for two crossing deletions but not for eight at once: 79–83 of 100 account deletions failed on 8 clients. Since round 3 the deletions take the deletion lock first (see [Service layer](#service-layer)), so two deletions that change derived rows never run at the same time, and `ConcurrencyTest.crossingAccountDeletionsInCallerTransactionsNeverDeadlock` checks it without retries. Rows of videos that are being deleted are skipped; their counters go with them.
- **Co-view rows of a deleted video.** Until round 2 `video_coviews` had two foreign keys into `videos`, so deleting a video deleted its rows in two cascades, first `(v, x)` and then `(x, v)`. Two videos deleted at once took the rows between them in crossing orders, `(a, b)` then `(b, a)` for one and the reverse for the other, and deadlocked (4–6 of 8 deletions of imported videos failed on 8 clients). Since round 3 (`1d39e53`) the table has no foreign keys; the statement trigger `derived_videos_delete` locks the deleted videos' rows in both directions in `(bv, other_bv)` order (`SELECT … ORDER BY … FOR UPDATE`, which locks in the sorted order) and then deletes them. `ConcurrencyTest.coviewedVideoDeletionsInCallerTransactionsNeverDeadlock` deletes two co-viewed videos at once inside caller transactions, with a slow row trigger on `video_coviews` to widen the window.
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
- "Before" runs the 2023 queries on schema `original`. It is the 2023 `build.sql` with its indexes, holding the rows of the benchmark import, analyzed.
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

**User recommendations.** The single query barely changes: 1.690 ms → 0.963 ms for mid 422017, the case whose friends watched the most (`user-rec.*.txt`). Users without friends fall back to the general recommendation, which is where the derived tables help (by a comment in `docs/explain/explain.sql`, 13 of the step's 22 cases with valid credentials; that count is not recorded elsewhere, and the share of the step's time was not measured). The benchmark step goes from 1,977 ms to 62 ms in the round-3 series (1,580 → 56 ms in round 1).

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

The median case (mid 1727180, 43 followings) goes from 330.955 ms to 11.032 ms. On tables that were never analyzed, the 2023 plan took 608.395 ms and 662.909 ms for the two cases (`friend-rec.before-nostats.txt`); see [the friend step](#planner-statistics-and-the-friend-step).

### Hotspots and danmu display

These two steps are cheap reads (56 and 382 calls) that the benchmark times on their first pass, and they are the only reads that are slower than in the 2023 code (13 → 23 ms and 46 → 89 ms by the medians of the round-3 series; 14 → 19 ms and 72 → 80 ms in round 2's). WriteBench's reads mode replays both steps' cases in a fresh JVM right after the import, five passes in a row, each pass on a parallel stream as the benchmark runs it (3 runs per version, alternating; [benchmark/round2/writebench/summary.md](benchmark/round2/writebench/summary.md)):

| Pass | Hotspots (56 cases), 2023 code | this version | Danmu display (382 cases), 2023 code | this version |
|---:|---:|---:|---:|---:|
| 1 | 36.2 [33.2–38.5] | 30.3 [24.7–31.5] | 71.6 [62.2–105.7] | 114.6 [107.1–123.6] |
| 2 | 13.7 [11.5–15.1] | 11.2 [11.1–13.1] | 50.6 [34.1–55.5] | 74.6 [70.7–79.0] |
| 3 | 13.9 [10.9–14.2] | 11.6 [11.4–13.8] | 38.3 [34.0–53.9] | 58.3 [48.5–59.6] |
| 4 | 8.7 [8.2–11.2] | 11.2 [9.6–11.2] | 34.9 [34.7–40.9] | 36.2 [30.3–59.9] |
| 5 | 8.8 [8.2–12.2] | 12.3 [9.8–12.3] | 29.5 [28.6–38.9] | 34.5 [29.4–41.7] |

Milliseconds per pass, median of the 3 runs [smallest–largest]; every answer was correct in every pass. The cost is in the first passes: from the fourth pass on the two versions are within each other's ranges. Hotspots are not slower here even on the first pass (30.3 against 36.2 ms); in the benchmark the step comes after the import and steps 2–3, which run far longer in the 2023 code through the same JDBC path, and whether that warm-up explains its slower benchmark step was not measured. Danmu display is slower here for the first three passes (114.6 against 71.6 ms on the first). On the server, a first execution costs little more than later ones: in a fresh `psql` session the hotspot query took 0.181 ms to plan and 0.176 ms to run the first time and 0.061–0.080 + 0.039–0.069 ms the next two times, the display query 0.143 + 0.062 ms and then 0.071–0.104 + 0.037–0.041 ms ([explain/first-call.after.txt](explain/first-call.after.txt), `docs/explain/first-call.sql`, EXPLAIN (ANALYZE, TIMING OFF) on the video with the most danmus). If each pooled connection pays what that fresh session paid, first executions add up to 10 × 0.26 ms to a hotspot pass and 10 × 0.09 ms to a display pass (the pool has at most 10 connections: Hikari's default, which neither the runner nor WriteBench changes). Where the rest of the 43 ms of the first display pass goes was not measured; JIT compilation of the new code path is a candidate.

### Search

The keywords "周杰伦 MV" are searched by a regular user. Before (`search.before.txt`), the 2023 code joins every view of every matching video to count views (51,157 joined rows for 2 matches), and splits titles with the keyword as a regular expression:

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

The benchmark's 11 write steps take 3,294 ms in the 2023 code and 3,771 ms in this version (sums of the step medians of the round-3 series, [benchmark/round3/plain](benchmark/round3/plain); per-run sums 3,025–3,451 and 3,594–4,301 ms). Collect (+147 ms), delete video (+143), like (+107) and danmu send (+94) make up most of the difference; coin is 67 ms and register 9 ms faster, and the other five together are 62 ms slower. In round 2's series the sums were 2,846 and 3,353 ms: danmu send +165 ms, like +95, collect +82, delete video +141, delete account 45 ms faster. Round 1 explained the difference with the timing after the import. Round 2 measured that explanation first, then the work per call; round 3 measured the 8-client runs and fixed the deletions.

**The timing after the import, round 2** ([benchmark/round2/plain/report.txt](benchmark/round2/plain/report.txt), [settle/report.txt](benchmark/round2/settle/report.txt); `scripts/bench_report.py`):

- Writeback is not the cause. At the start of step 10 the kernel's dirty memory (`Dirty` in `/proc/meminfo`) is 1.1–2.0 MB in this version's runs and 63–90 MB in the 2023 code's.
- A checkpoint is in progress during the write steps of every run of both versions. WAL-triggered checkpoints follow each other through both imports; the last one started 2–3 s before step 10 here and 24–29 s before it in the 2023 code.
- The settle variant runs a `CHECKPOINT` after the import and waits until dirty memory is below 64 MB. There the write steps took 3,189 ms here and 2,892 ms in the 2023 code, against 3,353 and 2,846 ms in the plain runs. The per-run sums overlap (this version: 3,194–3,507 ms plain, 3,041–3,267 ms settle), so the checkpoint costs this version about 5 %, which three runs per variant cannot separate from noise, and the 2023 code nothing measurable. Most of those 5 % are in danmu send (369 → 291 ms) and delete video (591 → 526 ms).
- Full-page images are written in both variants and by both versions: the first change of a page after a checkpoint has started copies the page into the WAL, whether or not the checkpoint has finished. The write steps write 112,797 kB of WAL in the 2023 code and 121,260 kB here (medians), 79,129 and 86,079 kB of it page images; per step, coin, like, collect and the two deletes are within 25 % of each other.
- The exception is danmu send: 2,307 kB instead of 112 kB, 2,052 kB of it page images. The foreign key `danmus.mid → users` checks the sender with a `FOR KEY SHARE` lock on its `users` row; the lock is a `Heap/LOCK` WAL record and, for the first lock on a page after the checkpoint, comes with an image of that page. Measured on its own ([experiments/fk-lock-wal.txt](experiments/fk-lock-wal.txt), `docs/experiments/fk-lock-wal.sh`, `pg_waldump --stats=record`): 200 danmus from 200 different users, inserted right after a `CHECKPOINT` in a transaction that is rolled back, wrote 1,514,237 bytes of WAL, 1,418,380 bytes of it page images attached to the 202 `Heap/LOCK` records; the same 200 danmus inserted again right afterwards wrote 77,487 bytes, with the same 202 lock records and no page images. Follow (297 → 619 kB) has two such foreign keys into `users`; that the cause is the same there was not measured.

**The work per call, round 2.** Round 3's runs of the same modes, with this round's jar and 3 runs per version and client count, are in [benchmark/round3/writebench/summary.md](benchmark/round3/writebench/summary.md) and the README's write-path table; in them no call failed. WriteBench ([benchmark/round2/writebench/summary.md](benchmark/round2/writebench/summary.md), `scripts/writebench/tables.py`) runs the benchmark's write operations through the service layer of either jar, on the benchmark data as that jar imports it, after a `CHECKPOINT` and 300 warm-up calls: 600 calls per operation (8 deletions of imported videos), 5 runs per version and client count, alternating, each under the bench lock. Medians of the per-run p50 (ms), 2023 code → this version, and the statements and round trips per call from its count mode:

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

8 clients: in this version's runs 4–6 of the 8 deletions of imported videos and 79–83 of the 100 account deletions failed with `deadlock detected` after 3 attempts (87 when round 3 re-ran the same jar), none in the 2023 code's; account deletions on 8 clients are runs of their own (3 per version, 100 calls each). This version's toggles send the lock statement and the toggle in one round trip.

- By the medians, coin donation (1.32 → 0.92 ms) and danmu like (0.76 → 0.60 ms) are faster per call at steady state; register, review, deleting a video without views and follow cost the same; like, collect and danmu send are 0.07–0.13 ms slower, post video 0.21 ms and update video 0.41 ms.
- The deletes that cascade are slower: deleting an imported video (about 20,000 views) takes 49.53 ms instead of 27.45 ms, deleting an account 6.57 ms instead of 5.38 ms. On the server, by EXPLAIN ANALYZE in rolled-back transactions on the data as this version imports it ([explain/delete-costs.after.txt](explain/delete-costs.after.txt), `docs/explain/delete-costs.sql`, trigger times summed per execution by `docs/explain/trigger-times.awk`; executions 2–5 of 5 in one session): deleting a video with 18,179 views, 10,429 likes, 10,129 coins and 10,064 favorites takes 28.9–30.7 ms, 23.9–25.9 ms of it in the foreign-key cascades and 4.2–4.5 ms in the derived-table trigger functions (the session's first execution: 38.7, 29.6 and 8.5 ms). Collecting the deleted rows into the triggers' transition tables happens inside the cascades and was not separated. Deleting an account with 23 views and 177 follow rows takes 11.7–15.1 ms, 10.0–13.2 ms of it in the derived-table triggers and 9.5–12.4 ms of that in the one on `viewers`, which updates the view counters and the 506 (23 × 22) co-view rows of those views; the cascades take 1.3–1.7 ms. Timed without EXPLAIN over 39 accounts that own no video (every 1,000th by mid, and that one; [explain/delete-account-sample.after.txt](explain/delete-account-sample.after.txt)), the server-side delete takes a median of 10.7 ms in a first pass and 10.0 ms in a second (6.1–18.3 and 4.9–17.0 ms), and it grows with the co-view rows it changes: 13.8 and 15.3 µs per row by least squares, correlation 0.79 and 0.92. WriteBench's p50 (6.57 ms, commit included) is lower than that median. Its account deletions run after its 8 deletions of imported videos, which take those videos' views with them, so its accounts have fewer views left; how many fewer, and whether that explains the whole difference, was not measured.
- The settle runs of the benchmark show the same split: coin, register, update video, review and delete account are not slower than the 2023 code there, while danmu send (+90 ms), like (+146), collect (+64), post video (+8), delete video (+80) and follow (+8) are.
- Coin is the exception between the two measurements: one statement instead of seven, 30 % faster per call in WriteBench, but not faster in the benchmark (547 → 567 ms plain, 556 → 515 ms settle, ranges overlapping). Both versions write about 7 MB of page images in that step; why the per-call gain does not show there was not measured.

**Triggers and foreign keys of one like** (pgbench, `scripts/write_latency.sh`, [experiments/write-latency.txt](experiments/write-latency.txt), one client, 1,000 transactions per measurement): with the derived-table triggers and the foreign keys 1.019 ms [0.935–1.064], without the triggers 0.887 ms [0.839–0.924], without the triggers and the foreign keys 0.794 ms [0.657–0.852] (median [range] of 5 alternating rounds). Without the triggers a like takes about 0.13 ms less, and the ranges with and without them do not overlap. Without the foreign keys as well it takes another 0.09 ms less, but those two ranges overlap (0.839–0.924 and 0.657–0.852 ms), so that difference is not separated from the noise. Right after the import and 120 s later the like took 0.957 and 0.994 ms (dirty memory 932 kB and 140 kB), so round 1's 1.30 → 0.78 ms, measured with 143 MB of dirty data after the import, did not recur. Part 3 of the script (the 2023 code's statement sequence in pgbench) needs the schema `original` and was skipped; WriteBench measures the 2023 code's own code instead.

**One coin donation, with EXPLAIN** ([explain/coin-donation.after.txt](explain/coin-donation.after.txt), `docs/explain/coin-donation.sql`, rolled back): user 51, who had 91 coins and had not donated to BV1PK4y1b7dt, donates a coin to it five times in one session, each time rolled back. The donation happens each time: the update of `users` returns its row (rows=1), the insert's input has one row, and the two foreign-key checks on `coins`, which fire once per inserted row, run once each (the `Insert on coins` node itself shows rows=0 because the statement returns nothing). The session's first execution takes 2.670 ms (planning 0.296 ms), most of it in the audit trigger (1.145 ms) and the counter trigger (0.750 ms), which run for the first time in the session there. The four later ones take 0.317–0.498 ms (planning 0.133–0.163 ms), of which the counter trigger takes 0.089–0.135 ms, the audit trigger 0.049–0.108 ms and the two foreign-key checks 0.047–0.075 ms together. Round 1's EXPLAIN of a coin donation measured one that inserted nothing; these numbers replace it.

**Under concurrency, round 2** (WriteBench, 8 clients, the jar `42004a16`): like, collect, post video and follow reach 24–35 % less throughput than in the 2023 code; danmu send 16 % more, register 24 % more and deleting a video without views 58 % more; coin, danmu like, update video and review are within each other's run ranges. Round 2 did not measure why; round 3 did (below). Deletions that cascade deadlocked with each other: in each of the 5 runs, 4–6 of the 8 deletions of imported videos failed with `deadlock detected` after 3 attempts (15–20 deadlocks per run), and in each of 3 runs of 100 account deletions on 8 clients 79–83 failed (310–316 deadlocks per run, p50 18 s); the 2023 code completed all of them (p50 121 ms and 24.8 ms). The retry in `support/Writes` (up to 3 attempts) is enough for two crossing deletions (45 trials of two crossing account deletions 0–8 ms apart: 4 failed with `deadlock detected` on the round-1 code and none on this version, where 2 deadlocks were detected and retried) but not for eight. Round 3 removed these deadlocks (below).

**Concurrent deletions, round 3.** Deletions now take the deletion lock first, and the co-view rows of a deleted video are deleted by `derived_videos` in one key-ordered pass (see [Service layer](#service-layer) and [Derived tables](#derived-tables)). The first version of that trigger (`1717da9`) locked and deleted the rows with `c.bv in (select bv from old_rows) or c.other_bv in (select bv from old_rows)`, which PostgreSQL evaluates as a filter over a sequential scan of `video_coviews`. A statement trigger that EXPLAINs this query on its own transition table ([experiments/coview-delete-plan.txt](experiments/coview-delete-plan.txt), `docs/experiments/coview-delete-plan.sql`, in a scratch database with the table and indexes of `sql/build.sql`, 44 videos and 1,892 co-view rows) shows a `Seq Scan` that removes all 1,892 rows by the filter, both when it deletes a video without co-view rows and when it deletes no video at all. Both statements of the trigger did that, on every video deletion and, because the cascade from `users` into `videos` fires the statement trigger with an empty transition table, on every account deletion too. WriteBench on `1717da9` ([benchmark/round3/writebench/1717da9](benchmark/round3/writebench/1717da9), 3 alternating runs per version and client count): deleting a video without views took 1.89 ms at 1 client (p50; 2023 code 1.25 ms, round 2 1.10 ms; with `ac3a0dc` 1.33 ms against the 2023 code's 1.15 ms, ranges disjoint), and deleting an account 7.03 ms (2023 code 5.28 ms; 6.92 against 5.93 ms with `ac3a0dc`, within the noise). The series was stopped after its first round. That round does not show the regression: run `c5-O1` ([benchmark/round3/1717da9-series](benchmark/round3/1717da9-series); it ran on a working tree with 6 uncommitted tracked files, `porcelain_lines=6` at `9b0077d`) took 405 ms in step 22, 1.09 times its paired run of the 2023 code (371 ms), the same ratio as in the final series (490 / 451); step 21 gave 1.30 and 1.29. The regression rests on the WriteBench runs and the plans. `ac3a0dc` joins the transition table to `video_coviews` instead (`join old_rows o on c.bv = o.bv or c.other_bv = o.bv`), which PostgreSQL runs as one probe of the primary key and one of the `other_bv` index per deleted video (a `BitmapOr`, not executed at all when no video is deleted; same file); all runs below are of that version.

WriteBench on 8 clients ([benchmark/round3/writebench/summary.md](benchmark/round3/writebench/summary.md), `scripts/writebench/tables3.py`; 3 runs each of the 2023 code and this version, alternating, each group of runs under one hold of the bench lock; round 2 = the jar of `a330c0c` with its SQL, one run each on the same boot, besides round 2's own runs). Account deletions: 100 calls per run after 20 warm-up calls, each deleting a different imported user who owns no video. Video deletions with views: the 8 deletions of imported videos (about 20,000 views each) in each 8-client steady run. Medians over the runs, [smallest–largest run]:

| | 2023 code | round 2 | this version |
|---|---:|---:|---:|
| deleteAccount: failed calls per run | 0, 0, 0 | 83, 79, 80 (round 2's runs); 87 (here) | 0, 0, 0 |
| deleteAccount: p50 ms | 29.35 [28.33–39.37] | 18,024.57 (round 2's runs); 19,064.06 (here) | 107.66 [104.64–109.58] |
| deleteAccount: p99 ms | 47.79 [39.40–77.34] | 42,052.38 (here) | 141.61 [130.26–175.26] |
| deleteAccount: calls per second | 244.4 [197.3–278.7] | 0.4 | 70.7 [69.9–71.8] |
| deleteVideoWithViews: failed of 8 per run | 0, 0, 0 | 4–6 (round 2's 5 runs); 4 (here) | 0, 0, 0 |
| deleteVideoWithViews: p50 ms | 133.50 [132.46–166.51] | 12,202 (round 2's runs); 14,190.33 (here) | 296.92 [253.07–309.58] |
| deleteVideoWithViews: p99 ms (of 8 calls, the second slowest) | 149.53 [132.98–185.05] | 16,221.92 (here) | 374.98 [333.50–462.85] |
| deleteVideo without views: p50 ms | 2.67 [2.01–2.67] | 1.83 (round 2's runs) | 2.17 [2.15–2.21] |
| deadlocks detected by the server per run | 0 | 310–316 and 15–20 (round 2's runs); 328 and 16 (here) | 0 |

No call failed and the server detected no deadlock in any round-3 run of this version, including the stress mode (45 trials each; crossing account deletions: 0 of 45 with an error, 0 deadlocks). The price is the serialization: on 8 clients an account deletion waits for the others, so it takes 3.7 times as long as the 2023 code's at the median (107.66 against 29.35 ms) and 70.7 of them complete per second against 244.4. A deletion holds the lock for about as long as it takes on the server, which is about 10 ms for an account (see above); 1,000 / 70.7 = 14.1 ms per deletion. Deleting imported videos on 8 clients takes 2.2 times as long as the 2023 code's (296.92 against 133.50 ms); those deletions share the lock and run at once, and at 1 client one already takes about twice as long (below), from the cascades into the derived tables.

**Deletions, round 4.** Two deletion paths changed after `ac3a0dc`. `b5bbd84`: the deletion lock is taken only for a deletion the caller may make (before, a refused call took the exclusive lock too), and follow rows count as interactions, so two new accounts that follow each other no longer delete their two `followings` rows in crossing orders without the lock. `6c3f7d3`: `derived_viewers` checks the isolation level on every firing, locks the viewer's other videos before it adds pairs, and deletes a lost pair only on an exact match. WriteBench re-ran the 8-client deletions on the final code, alternating with the 2023 code under one hold of the bench lock (3 account-deletion runs and 1 steady run per version; [benchmark/round4/writebench/summary.md](benchmark/round4/writebench/summary.md)). Account deletions: p50 105.61 [102.09–111.37] ms against 24.25 [22.44–27.55] ms (round 3: 107.66 against 29.35), p95 144.57 against 40.30 ms, p99 148.56 [134.93–172.78] against 41.40 [40.04–48.11] ms (round 3: 141.61 against 47.79), 69.8 [69.0–75.2] against 306.2 [267.9–318.0] per second (round 3: 70.7 against 244.4), so the throughput ratio went from 0.29 to 0.23. This version's p50 and throughput ranges overlap round 3's; the change in the ratio comes mostly from the 2023 code's throughput (244.4 → 306.2 per second). Deleting a video with about 20,000 views: 250.98 against 129.41 ms (one run each; round 3: 296.92 against 133.50 ms over three), a throughput ratio of 0.40 (round 3: 0.385); without views 2.05 against 1.92 ms (0.94 of the 2023 code's throughput; round 3: 1.30). No call failed and the server detected no deadlock in any of these runs. The other operations of the steady run reached 0.75–0.90 of the 2023 code's throughput (send danmu, danmu like, like, collect, post video, register, review, follow), 1.18 for coin and 1.78 for update video (whose 2023-code run had a p99 of 44.35 ms); these are single runs and are not compared with round 3's.

**Stress mode, round 4** (same summary). WriteBench now reports a toggle trial's failed calls and count mismatches separately and keeps the error messages, and it applies the count check only when a sequential probe shows that the toggle returns the new state. Final code: coin donations overspent in 0 of 45 trials (85 donations done); like and follow toggles had no failed call, and 0 of the 45 count-checked trials of each lost an update; crossing account deletions failed in 0 of 45 trials, with 0 deadlocks; no connection was left open. 2023 code: coin donations overspent in 45 of 45 trials (252 donations done). Its like toggles had failed calls in 45 of 45 trials, 1,296 of 3,600 calls in all, each `duplicate key value violates unique constraint "videolikes_pkey"`: the code checks for the row and then inserts it, so two concurrent calls can both insert, and the exception skips `conn.close()`, which leaves the connection open. Follow failed the same way on `followings_pkey` (1,410 calls), and the probe found that its follow also returns `true` for an unfollow, so the count check does not apply to it; no trial of either toggle could be count-checked. The WriteBench of round 2 counted any failed call as a lost update, so its 45 of 45 for the 2023 code's toggles (1,326 and 1,474 failed calls) are failed calls, not lost updates; the round-1 rewrite's 45 of 45 had no failed call and are lost updates (round 2's summary now labels the columns that way).

**Where the 8-client time goes** (round 3; runs `diag-orig-c8` and `diag-r2-c8` in [benchmark/round3/writebench](benchmark/round3/writebench), table in its [summary.md](benchmark/round3/writebench/summary.md)). One 8-client steady run of the 2023 code and one of the round-2 jar, with WriteBench's `--sample`: during each operation a separate connection reads `pg_stat_activity` about every 2 ms and counts what the other client backends of the database are doing, and the CPU time of those backends and of the WriteBench JVM is read from `/proc/<pid>/stat` (10 ms ticks) at the start and end of the operation. The sampling costs throughput, so these runs are for attribution only. The round-3 changes do not touch these operations.

- **Near CPU saturation, load generator included.** Backend plus JVM CPU time per call, times the throughput, gives the vCPUs busy during the operation: 3.00–3.68 of the machine's 4 for every operation of the 2023 code, and 3.03–3.61 for every operation of round 2 except follow (2.53). About two thirds of that CPU is the WriteBench JVM (the load generator and the sampler), which is not attributed further. This is one sampled run per version, not interleaved (the 2023 code at 13:26 UTC, round 2 at 13:42 UTC); it does not show that the database is CPU-bound: in these runs more backends waited for WAL flushes (below) than ran on CPU.
- **Throughput fell roughly in proportion to the CPU time per call.** That follows almost directly from the near-constant busy vCPUs (busy = CPU per call × throughput), so the finding is only that the cost is CPU per call, not locking. Like: 0.95 ms per call in the 2023 code (0.30 in the backend, 0.65 in the JVM), 1.58 ms in round 2 (0.58 and 1.00); throughput 3,266 → 2,073 per second, a ratio of 0.63 where the CPU times give 0.60. Collect: 0.92 → 1.52 ms, 3,614 → 1,995 per second (0.55 against 0.60). Post video: 0.78 → 1.50 ms, 3,834 → 2,260 per second (0.59 against 0.52). Danmu send: 1.10 → 1.75 ms, 3,345 → 2,061 per second (0.62 against 0.63). Coin: 1.67 → 1.77 ms, 1,998 → 1,896 per second (0.95 against 0.94).
- **Not hot counter rows, not lock waits.** Backends waiting for a row lock (`Lock:transactionid` plus `Lock:tuple`) average 0.20 of 8 per sample for like and 0.42 for collect in round 2 (0 in the 2023 code). In these five operations both versions have 2.7–4.9 backends per sample waiting for a commit's WAL flush (`LWLock:WALWrite` plus `IO:WALSync`), without a pattern that separates the versions; for like, collect, post video and danmu send 0.2–1.6 backends per sample were on CPU.
- **Where the extra CPU goes, as far as measured.** On the server, round 2's pgbench measurement put the triggers of one like at about 0.13 ms and its foreign keys at about 0.09 ms (one client); the backend's extra 0.28 ms per like in these runs is of that size. The JVM's extra 0.35 ms per like (although this version sends 2 round trips per like instead of 5) was not broken down: JFR execution samples of 8-client like runs had 3 (2023 code) and 40 (round 2) samples in the operation's window, too few to attribute. The JVM's CPU time includes the sampler's own thread.
- **Follow is the exception.** Its CPU time per call is the same in both versions (0.82 ms), its throughput drops 4,192 → 3,097 per second, and round 2 keeps only 2.53 vCPUs busy, so its loss is not CPU; the samples show 4.88 backends per sample waiting for the WAL flush against 4.33. Not attributed further.

The extra backend CPU is the derived tables' upkeep, which buys the read steps (20.0 s → 1.09 s in the round-3 series; 19.1 s → 1.08 s in round 2's); round 2 measured an early return in the triggers without a gain separable from noise ([Tried and rejected](#tried-and-rejected)). It was not removed. In the round-3 steady runs without sampling (3 per version, [summary.md](benchmark/round3/writebench/summary.md)), this version reaches 0.78 of the 2023 code's 8-client throughput for like, 0.85 for collect, 0.90 for post video, follow and danmu send, and 0.79 for danmu like; round 2's 5 runs gave 0.65, 0.68, 0.72 and 0.76 for like, collect, post video and follow. These operations did not change between the rounds, so that difference is the spread of 8-client runs on this shared machine, not a gain.

## Planner statistics and the friend step

In round 1 the baseline's friend step took 1,932, 8,910 and 9,008 ms. The explanation offered then was that the 2023 importer never runs `ANALYZE`, so the plan depended on whether autovacuum had analyzed `followings` by step 8. Round 2 tested it ([benchmark/round2](benchmark/round2), `scripts/bench_report.py`; `bench_instrumented.py` records `pg_stats` and `pg_stat_user_tables` for `followings` and the plan of the step's median query, user 18398, when step 7 ends):

| 2023 implementation | Step 8 | Statistics of `followings` at step 8 | Plan |
|---|---:|---|---|
| as it is (plain runs `c2-B1..3`) | 11,970 / 12,768 / 12,928 ms | from autovacuum during the import: one or two autoanalyze runs, 0.9–2.5 M rows changed since; `n_distinct(mid1)` 9,768–13,051 | hash anti-join over a hash join |
| `VACUUM ANALYZE` right after the import (`c4-B1..3`) | 11,671 / 11,440 / 12,183 ms | analyzed in the pause; `n_distinct(mid1)` 14,590–14,673 | the same |

- With fresh statistics the 2023 code is not faster, and the plan is the same. So missing statistics do not explain the 2023 code's friend step in round 2, and the 1,932 ms run of round 1 remains unexplained.
- Round 1's `EXPLAIN` without statistics (`friend-rec.before-nostats.txt`, 608 and 663 ms against 331 and 441 ms) is a per-query difference of 1.5–1.8× on another day; it does not carry over to the step.
- This version's importer ends with `VACUUM (ANALYZE)` (`n_mod_since_analyze` is 0 at step 8). Its friend step took 519, 686 and 708 ms in round 2, 18.6× faster than the 2023 code's median there (round 3: 616 against 13,694 ms, 22×); the covering index `followings (followee_mid, follower_mid)` and the rewritten query are what changed.

## Development checkpoints

These are single benchmark runs made with the same harness during development, each on the working tree just before the commit named. They are not repeated, and the write steps vary a lot between identical runs (over the three round-1 runs of the code of that time, like took 571–837 ms and coin 374–1,090 ms), so only the large changes mean anything. Raw files: [`benchmark/round1/dev`](benchmark/round1/dev).

| Step (ms) | baseline, median of round 1 | m1 core (`66edb47`) | m2 derived | m3 upsert | m4 friends (`95a7e83`) | round 1, median |
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

`docs/benchmark/round1/dev/script-check.json` is one run of `scripts/benchmark.sh` on the round-1 code. It shows that the script in this repository reproduces the procedure: 5,622 / 5,622 passed, an import of 8,471 ms, and 3,899 ms for steps 2–23.

Command: `cd docs/benchmark/round1/dev && python3 -I ../../../../scripts/compare.py m1=m1_core.json m2=m2_derived.json m3=m3_upsert.json m4=m4_friends.json` (run at the time with the same script in `/home/user/work/bench6`).

## Tests

There are 150 tests (JUnit 5, counted as JUnit reports them: each argument set of a parameterized test is one test; 119 test methods, 6 of them parameterized); 116 of them, in 11 classes, are integration tests against PostgreSQL 16 in Testcontainers, the other 34 are unit tests. `./gradlew build` runs them, plus `BulkImporterTest` a second time with the JVM in the benchmark's time zone (Asia/Shanghai, task `importerTestInBenchmarkZone`): 159 test executions. The CI workflow (`.github/workflows/ci.yml`) runs the same build on JDK 17 and 21, but it has never run on GitHub, because this branch has not been pushed. Its build command was run locally in a fresh clone (`git clone --no-local`, `core.fileMode` true, `gradlew` and the scripts committed as mode 100755): it passes on JDK 21 and, in an `eclipse-temurin:17-jdk` container, on JDK 17 ([experiments/ci-proof.txt](experiments/ci-proof.txt), [ci-proof-jdk21.log](experiments/ci-proof-jdk21.log), [ci-proof-jdk17.log](experiments/ci-proof-jdk17.log); `--offline --max-workers=2` added because the machine is shared, and `--no-build-cache` so that every task runs although `gradle.properties` enables Gradle's local build cache).

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
| `ConcurrencyTest` | 16 | concurrent toggles, edits and co-view updates (lost updates), crossing account deletions, a video and its liker deleted at once, and three cascades run inside caller transactions, where nothing retries them: crossing account deletions and two co-viewed videos deleted at once (both deadlocked until round 2), and two new accounts that follow each other (round 3's lock-skipping path); a refused deletion takes no deletion lock; a view inserted during the deletion of one of the viewer's videos adds no pair of it |
| `TransactionCompositionTest` | 4 | refused writes inside a caller's transaction leave it usable |
| `CollationIndependenceTest` | 5 | bv tie-breaks in byte order in a database whose default collation is ICU |
| `DerivedDataConsistencyTest` | 7 | randomized workloads (seeds 1, 20231230, 424242; 400 steps each; 18 kinds of service call or multi-row SQL). Every 50 steps, `video_stats` and `video_coviews` must equal a recomputation from scratch, and each kind of action must have changed something. Also: a co-view count below its loss fails the deletion instead of disappearing, view changes are refused under REPEATABLE READ (and allowed under SERIALIZABLE), the key of a `viewers` row cannot be updated |
| `UserControlScriptTest` | 1 | loads `sql/usercontrol.sql` in a rolled-back transaction: the table, column and function rights of each role, and statements run as each role (denied with SQLSTATE 42501, or allowed through the `SECURITY DEFINER` trigger functions and `authenticate_user`) |
| `BulkImporterTest` | 9 | stored data equals the input, sequences continue, the catalog is unchanged, statistics exist, refusals, and failures (load, indexes, constraints) leave empty tables with the complete schema and an audit trail |
| `DerivedDataTest` | 3 | the dense and the sparse co-view computation against brute force |
| `DatabaseServiceTest` | 3 | truncate (the audit log keeps one `TRUNCATE` record), group members, the sum helper |
| `WritesTest` | 5 | deadlock retries, SQLSTATE mapping |
| `ServiceHelpersTest`, `ClockConfigTest`, `PageTest` | 26 | keyword splitting, birthdays, sex labels, the pinned clock, paging arithmetic |

**Do the concurrency tests test anything?** `ConcurrencyTest`, `TransactionCompositionTest` and `CollationIndependenceTest` were run against the code before the fixes (commit `978fe40` with the new tests copied in; `TransactionCompositionTest` without its one test of `support/Writes`, which did not exist yet) and against this version ([experiments/concurrency-tests-old-vs-new.txt](experiments/concurrency-tests-old-vs-new.txt)). On the old code 15 of the 19 fail: 10 of the 11 in `ConcurrencyTest` (all but `deletingAVideoAndOneOfItsLikersAtOnceBothSucceed`, which passes there too), 4 of the 5 in `CollationIndependenceTest` (all but `theDatabaseReallySortsCaseFirstByLanguage`, which checks the test database itself) and 1 of the 3 in `TransactionCompositionTest` (`aDuplicateTitleInAnUpdateLeavesTheTransactionUsable`; the post and donation cases already passed). On this version all 20 pass. The two cases added in round 3 fail on the round-2 code (`a330c0c` with the new `ConcurrencyTest`; `DeadlockLoserDataAccessException` in both) and pass on the fixed code ([experiments/deadlock-tests-old-vs-new.txt](experiments/deadlock-tests-old-vs-new.txt)). The five cases added in round 4 (three in `ConcurrencyTest`, two in `DerivedDataConsistencyTest`) all fail on the round-3 code (`10807b2` with the new test classes): the two mutually following accounts deadlock (`DeadlockLoserDataAccessException`), the refused deletion holds the lock (1 lock instead of 0), the view inserted during the deletion leaves 2 co-view rows of the deleted video, and the drifted count and the REPEATABLE READ insert raise no error; all pass on the fixed code ([experiments/round4-tests-old-vs-new.txt](experiments/round4-tests-old-vs-new.txt)). `UserControlScriptTest` was not run against the old `usercontrol.sql`, whose `normaluser` could read every password.

## Tried and rejected

- **Dropping the foreign keys for write speed.** In pgbench a like without the derived-table triggers took 0.887 ms with the foreign keys and 0.794 ms without them (medians of 5 alternating rounds whose ranges overlap; [experiments/write-latency.txt](experiments/write-latency.txt)), a difference the measurement cannot separate from noise. Without the foreign keys, the cascades would have to be written by hand again.
- **`CHECK (>= 0)` on the counters.** These are incompatible with signed-delta upserts; see [Derived tables](#derived-tables).
- **Rebuilding `video_coviews` in SQL after the load.** That needs a self-join of all 875 k views on the user. `DerivedData` instead counts the pairs in Java, during the load phase, while the records are in memory.
- **An early return in the derived-table triggers.** A toggle fires both its insert and its delete trigger, and one of the two has an empty transition table; the deletes run the co-view branch for videos that are being deleted anyway. A variant of `sql/build.sql` that returns early in both cases ([benchmark/round2/b1/build-early-return.sql](benchmark/round2/b1/build-early-return.sql)) was measured with WriteBench against the current triggers: 5 alternating rounds, 1 client, after a checkpoint, 300 warm-up and 600 measured calls per operation. Median p50 per call, current vs early return: sendDanmu 0.976 vs 1.125 ms, coinVideo 1.076 vs 1.078, likeVideo 0.988 vs 1.050, collectVideo 0.962 vs 0.873, deleting a video with views 35.6 vs 33.7, deleteAccount 6.75 vs 6.57 ms; every range of one variant overlaps the other's ([benchmark/round2/b1](benchmark/round2/b1), `scripts/writebench/summarize.py`). No gain that the measurement can separate from noise, so it was not kept. The experiment has a gap: its runs left out postVideo (`--only` lists the seven operations above), so deleteVideo found no video to delete and returned true in 0 of its 600 calls per run; deletions of videos without views were not measured. Its `code_sha` values (`cad2e6e+b1`, `cad2e6e+new`) are labels chosen by hand.
- **Not tried: server settings.** Parallel query, JIT and the checkpoint settings stay as the shared server has them for every run.

## Open questions

- **Account deletions run one at a time.** The deletion lock removed the deadlocks (see [The write path](#the-write-path)), but on 8 clients an account deletion now waits for the others: each spends about 10 ms on the server updating the counters and co-view rows of its views, under the lock. Locking the affected `video_stats` and `video_coviews` rows in one global order before the cascade (all counters in `bv` order, then all co-view rows in key order) would let deletions that touch different rows run at once, and leave the retry for rows that two deleted users share (a follow between them, for example); not tried. Storing each co-view pair once (`bv < other_bv`) would halve the co-view rows a view changes.
- **The co-view delete branch.** Deleting an account spends most of its server time in `derived_viewers` (9.5–12.4 of 11.7–15.1 ms for an account with 23 views, measured on round 2's SQL, see [The write path](#the-write-path)), which locks the affected co-view rows, deletes those that reach zero and updates the rest, in three statements. One sorted decrement could replace them; it needs `CHECK (viewers > 0)` dropped (an upsert checks the proposed row first), a cleanup of zero rows, and counts that are exact. Not tried in round 2 or round 3; with account deletions serialized it would shorten the time each holds the deletion lock.
- **Writes under concurrency.** On 8 clients, in one sampled run per version, the VM was near CPU saturation (load generator included) and row-lock waits were small; this version spends more CPU time per call, in the backend (the derived-table triggers and the foreign keys) and in the JVM (see [The write path](#the-write-path)). The JVM's share (0.35 ms more per like although this version makes fewer round trips) is not explained; a profile with more samples per operation (async-profiler, or JFR over longer runs) would be the next step. On the server side, round 2 measured skipping the empty trigger firing of a toggle without a gain separable from noise ([Tried and rejected](#tried-and-rejected)).
- **Coin donation in the benchmark.** One statement and faster per call at steady state; its benchmark step took 547 → 567 ms in round 2 and 636 → 569 ms in round 3, with overlapping ranges both times, so whether the step gains is not settled.
- **Authentication is a separate query.** Folding it into each write statement would save one round trip per call (every write except register takes two round trips today, the authentication query and the write; WriteBench count mode), at the cost of more complex SQL.
- **Writes right after the import.** A `CHECKPOINT` at the end of the import would take the checkpoint out of the write steps (about 5 % of them, see above). It needs a superuser or `pg_checkpoint` membership, which the `sustc` role lacks.

## Interpretations of the specification

The interfaces' Javadoc leaves some cases open. Each choice below keeps the result well defined. The service test classes cover them; the four that had no test in round 1 (unreviewed videos in recommendations, an empty sign, `likeDanmu` on an unpublished video, the filter's tie-break by id) now have tests too.

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
