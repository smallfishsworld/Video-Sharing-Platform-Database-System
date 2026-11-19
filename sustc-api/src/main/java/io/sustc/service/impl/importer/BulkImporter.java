package io.sustc.service.impl.importer;

import io.sustc.dto.DanmuRecord;
import io.sustc.dto.UserRecord;
import io.sustc.dto.VideoRecord;
import io.sustc.service.impl.importer.ParallelTasks.NamedTask;
import io.sustc.service.impl.support.Strings;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Loads the benchmark dataset into an empty database.
 *
 * <ol>
 *   <li>The indexes, primary/unique constraints, foreign keys and {@code derived_*} triggers of all
 *       tables are read from the catalog and dropped ({@link DeferredSchemaObjects}).
 *   <li>Every table is loaded on its own connection with {@code TRUNCATE} + {@code COPY ...
 *       FREEZE} in one transaction. Frozen rows on all-visible pages mean index-only scans work
 *       right away, without a VACUUM pass to set the visibility map. The derived tables
 *       ({@code video_stats}, {@code video_coviews}) are computed from the records in memory
 *       ({@link DerivedData}) and loaded the same way.
 *   <li>The indexes are rebuilt in parallel, largest table first, and the constraints are
 *       re-attached to them. Foreign keys are added {@code NOT VALID} and then validated, one
 *       table per task, and the triggers are recreated.
 *   <li>{@code VACUUM (ANALYZE)} gives the planner statistics, and the identity sequences are moved
 *       past the imported ids.
 * </ol>
 *
 * The table loads, index builds, foreign-key validations and VACUUM (ANALYZE) run as tasks on a
 * pool of {@code sustc.import.parallelism} threads (default 4), each task with its own pooled
 * connection, so the connection pool must allow at least that many connections. Capturing,
 * dropping and re-attaching the schema objects, creating the triggers and resetting the
 * sequences run on the calling thread.
 *
 * <p>If any step fails, the imported tables are truncated and the schema objects restored before
 * an {@link ImportException} naming the failed phase is thrown, so the imported tables are left
 * empty with their complete schema. The users audit log ({@code sql/audit.sql}) is not an
 * imported table: it keeps the INSERT records of the failed attempt's users and a TRUNCATE record
 * for every truncation of users, so the failed attempt stays visible in the trail.
 */
@Component
@Slf4j
public class BulkImporter {

    /** Every table that the import fills, including the derived tables. */
    static final List<String> TABLES = List.of(
            "users",
            "followings",
            "videos",
            "viewers",
            "videolikes",
            "coins",
            "favorites",
            "danmus",
            "danmulikes",
            "video_stats",
            "video_coviews");

    private static final String IDENTITY_COLUMNS =
            """
            select c.relname as table_name, a.attname as column_name
            from pg_attribute a
            join pg_class c on c.oid = a.attrelid
            where c.relnamespace = current_schema()::regnamespace and a.attidentity <> '' and not a.attisdropped
              and c.relname = any (?)
            """;

    /** How long a failed import waits for its remaining tasks to stop before it cleans up. */
    private static final long TASK_STOP_TIMEOUT_MINUTES = 5;

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final int parallelism;

    public BulkImporter(JdbcTemplate jdbc, @Value("${sustc.import.parallelism:4}") int parallelism) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("sustc.import.parallelism must be at least 1");
        }
        this.jdbc = jdbc;
        this.dataSource = Objects.requireNonNull(jdbc.getDataSource());
        this.parallelism = parallelism;
    }

    /**
     * Imports the records.
     *
     * @throws IllegalStateException if an imported table is not empty; nothing is changed then
     * @throws ImportException if the import fails; see the class comment for the state it leaves
     */
    public void importData(List<DanmuRecord> danmus, List<UserRecord> users, List<VideoRecord> videos) {
        requireEmptyTables();
        long start = System.nanoTime();
        DeferredSchemaObjects deferred = DeferredSchemaObjects.capture(jdbc, TABLES);

        Map<String, Long> rowCounts = new ConcurrentHashMap<>();
        AtomicReference<String> phase = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, threadFactory());
        try {
            timed(phase, "drop schema objects", () -> deferred.dropAll(jdbc));
            timed(phase, "load", () -> ParallelTasks.runAll(pool, loadTasks(danmus, users, videos, rowCounts)));
            timed(phase, "indexes", () -> ParallelTasks.runAll(pool, indexTasks(deferred, rowCounts)));
            timed(phase, "constraints", () -> {
                deferred.attachConstraints(jdbc);
                deferred.addForeignKeysNotValid(jdbc);
                ParallelTasks.runAll(pool, validationTasks(deferred));
                deferred.createTriggers(jdbc);
            });
            timed(phase, "vacuum analyze", () -> ParallelTasks.runAll(pool, vacuumAnalyzeTasks(rowCounts)));
            timed(phase, "identity sequences", this::resetIdentitySequences);
        } catch (RuntimeException e) {
            shutdownAndWait(pool);
            ImportException failure = new ImportException("Import phase '" + phase.get() + "' failed", e);
            restoreEmptySchema(deferred, failure);
            throw failure;
        } finally {
            pool.shutdownNow();
        }
        log.info("Imported {} in {} ms", rowCounts, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    private void requireEmptyTables() {
        String query = String.join(
                " or ",
                TABLES.stream()
                        .map(t -> "exists (select 1 from " + DeferredSchemaObjects.quote(t) + ")")
                        .toList());
        if (Boolean.TRUE.equals(jdbc.queryForObject("select " + query, Boolean.class))) {
            throw new IllegalStateException("importData expects empty tables; call truncate() first");
        }
    }

    private List<NamedTask> loadTasks(
            List<DanmuRecord> danmus, List<UserRecord> users, List<VideoRecord> videos, Map<String, Long> rowCounts) {
        // The biggest table first, so that it is not left running alone at the end.
        return List.of(
                load("followings", "follower_mid, followee_mid", rowCounts, w -> writeFollowings(w, users)),
                load("viewers", "bv, mid, view_time", rowCounts, w -> writeViewers(w, videos)),
                load("videolikes", "bv, mid", rowCounts, w -> writePairs(w, videos, VideoRecord::getLike)),
                load("coins", "bv, mid", rowCounts, w -> writePairs(w, videos, VideoRecord::getCoin)),
                load("favorites", "bv, mid", rowCounts, w -> writePairs(w, videos, VideoRecord::getFavorite)),
                load(
                        "users",
                        "mid, name, sex, birthday, level, coin, sign, identity, password, qq, wechat",
                        rowCounts,
                        w -> writeUsers(w, users)),
                load(
                        "videos",
                        "bv, title, owner_mid, owner_name, commit_time, review_time, reviewer, public_time,"
                                + " duration, description",
                        rowCounts,
                        w -> writeVideos(w, videos)),
                load("danmus", "id, bv, mid, time, content, post_time", rowCounts, w -> writeDanmus(w, danmus)),
                load("danmulikes", "danmu_id, mid", rowCounts, w -> writeDanmuLikes(w, danmus)),
                load(
                        "video_stats",
                        "bv, viewers, view_time_sum, likes, coins, favorites, danmus",
                        rowCounts,
                        w -> DerivedData.writeVideoStats(videos, danmus, w)),
                load("video_coviews", "bv, other_bv, viewers", rowCounts, w -> DerivedData.writeCoviews(videos, w)));
    }

    private static void writeFollowings(CopyRowWriter w, List<UserRecord> users) throws SQLException {
        for (UserRecord u : users) {
            checkInterrupted();
            for (long followee : DerivedData.orEmpty(u.getFollowing())) {
                w.value(u.getMid()).value(followee).endRow();
            }
        }
    }

    private static void writeViewers(CopyRowWriter w, List<VideoRecord> videos) throws SQLException {
        for (VideoRecord v : videos) {
            checkInterrupted();
            long[] mids = DerivedData.orEmpty(v.getViewerMids());
            float[] times = v.getViewTime() == null ? new float[0] : v.getViewTime();
            for (int i = 0; i < mids.length; i++) {
                w.value(v.getBv()).value(mids[i]).value(times[i]).endRow();
            }
        }
    }

    private static void writeUsers(CopyRowWriter w, List<UserRecord> users) throws SQLException {
        for (UserRecord u : users) {
            w.value(u.getMid())
                    .value(u.getName())
                    .value(u.getSex())
                    .value(Strings.emptyToNull(u.getBirthday()))
                    .value(u.getLevel())
                    .value(u.getCoin())
                    .value(u.getSign())
                    .value(u.getIdentity().name())
                    .value(u.getPassword())
                    // Empty OIDC ids mean "none" and must not collide in the unique index.
                    .value(Strings.emptyToNull(u.getQq()))
                    .value(Strings.emptyToNull(u.getWechat()))
                    .endRow();
        }
    }

    private static void writeVideos(CopyRowWriter w, List<VideoRecord> videos) throws SQLException {
        for (VideoRecord v : videos) {
            w.value(v.getBv())
                    .value(v.getTitle())
                    .value(v.getOwnerMid())
                    .value(v.getOwnerName())
                    .value(v.getCommitTime())
                    .value(v.getReviewTime())
                    .value(v.getReviewer())
                    .value(v.getPublicTime())
                    .value(v.getDuration())
                    .value(v.getDescription())
                    .endRow();
        }
    }

    /** Danmu ids are assigned in input order, starting from 1. */
    private static void writeDanmus(CopyRowWriter w, List<DanmuRecord> danmus) throws SQLException {
        long id = 1;
        for (DanmuRecord d : danmus) {
            w.value(id++)
                    .value(d.getBv())
                    .value(d.getMid())
                    .value(d.getTime())
                    .value(d.getContent())
                    .value(d.getPostTime())
                    .endRow();
        }
    }

    /** Uses the same ids as {@link #writeDanmus}. */
    private static void writeDanmuLikes(CopyRowWriter w, List<DanmuRecord> danmus) throws SQLException {
        long id = 1;
        for (DanmuRecord d : danmus) {
            for (long mid : DerivedData.orEmpty(d.getLikedBy())) {
                w.value(id).value(mid).endRow();
            }
            id++;
        }
    }

    @FunctionalInterface
    private interface RowSource {
        void writeTo(CopyRowWriter writer) throws SQLException;
    }

    private NamedTask load(String table, String columns, Map<String, Long> rowCounts, RowSource rows) {
        return new NamedTask("load " + table, () -> rowCounts.put(table, copy(table, columns, rows)));
    }

    /** TRUNCATE + COPY FREEZE in one transaction; returns the number of rows loaded. */
    private long copy(String table, String columns, RowSource rows) throws SQLException {
        String quoted = DeferredSchemaObjects.quote(table);
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (Statement st = conn.createStatement()) {
                    st.execute("truncate " + quoted);
                }
                CopyIn copyIn = conn.unwrap(PGConnection.class)
                        .getCopyAPI()
                        .copyIn("copy " + quoted + " (" + columns + ") from stdin with (freeze)");
                long rowCount;
                try {
                    CopyRowWriter writer = new CopyRowWriter(copyIn);
                    rows.writeTo(writer);
                    rowCount = writer.finish();
                } finally {
                    if (copyIn.isActive()) {
                        copyIn.cancelCopy();
                    }
                }
                conn.commit();
                return rowCount;
            } catch (SQLException | RuntimeException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            }
        }
    }

    private static void writePairs(CopyRowWriter w, List<VideoRecord> videos, Function<VideoRecord, long[]> mids)
            throws SQLException {
        for (VideoRecord v : videos) {
            checkInterrupted();
            for (long mid : DerivedData.orEmpty(mids.apply(v))) {
                w.value(v.getBv()).value(mid).endRow();
            }
        }
    }

    /** One task per index, the indexes of the largest tables first. */
    private List<NamedTask> indexTasks(DeferredSchemaObjects deferred, Map<String, Long> rowCounts) {
        return deferred.indexes().stream()
                .sorted(Comparator.comparingLong(
                                (DeferredSchemaObjects.Index i) -> rowCounts.getOrDefault(i.table(), 0L))
                        .reversed())
                .map(i -> new NamedTask("index " + i.name(), () -> jdbc.execute(i.definition())))
                .toList();
    }

    private List<NamedTask> validationTasks(DeferredSchemaObjects deferred) {
        return deferred.validationStatementsByTable().entrySet().stream()
                .map(e -> new NamedTask("validate foreign keys of " + e.getKey(), () -> e.getValue()
                        .forEach(jdbc::execute)))
                .toList();
    }

    private List<NamedTask> vacuumAnalyzeTasks(Map<String, Long> rowCounts) {
        return TABLES.stream()
                .sorted(Comparator.comparingLong((String t) -> rowCounts.getOrDefault(t, 0L))
                        .reversed())
                .map(t -> new NamedTask(
                        "vacuum analyze " + t,
                        () -> jdbc.execute("vacuum (analyze) " + DeferredSchemaObjects.quote(t))))
                .toList();
    }

    private record IdentityColumn(String table, String column) {}

    /**
     * Makes the next generated id follow the largest imported one. The columns are read first and
     * the sequences set afterwards, so that only one pooled connection is in use at a time.
     */
    private void resetIdentitySequences() {
        List<IdentityColumn> columns = jdbc.query(
                IDENTITY_COLUMNS,
                (rs, i) -> new IdentityColumn(rs.getString("table_name"), rs.getString("column_name")),
                (Object) TABLES.toArray(String[]::new));
        for (IdentityColumn c : columns) {
            String table = DeferredSchemaObjects.quote(c.table());
            jdbc.queryForObject(
                    "select setval(pg_get_serial_sequence(?, ?), coalesce(max("
                            + DeferredSchemaObjects.quote(c.column()) + "), 0) + 1, false) from " + table,
                    Long.class,
                    table,
                    c.column());
        }
    }

    /** Empties the imported tables and recreates missing schema objects; log_users is kept. */
    private void restoreEmptySchema(DeferredSchemaObjects deferred, RuntimeException failure) {
        try {
            jdbc.execute("truncate "
                    + String.join(
                            ", ",
                            TABLES.stream().map(DeferredSchemaObjects::quote).toList()) + " restart identity cascade");
            deferred.restoreMissing(jdbc);
        } catch (RuntimeException e) {
            failure.addSuppressed(e);
            log.error("Could not restore the schema after a failed import; recreate it from sql/build.sql", e);
        }
    }

    private void timed(AtomicReference<String> current, String phase, Runnable step) {
        current.set(phase);
        long start = System.nanoTime();
        step.run();
        log.info("Import phase '{}' took {} ms", phase, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    private static void shutdownAndWait(ExecutorService pool) {
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(TASK_STOP_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                log.warn("Import tasks did not stop within {} minutes", TASK_STOP_TIMEOUT_MINUTES);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new ImportException("Import cancelled");
        }
    }

    private static ThreadFactory threadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "sustc-import-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
