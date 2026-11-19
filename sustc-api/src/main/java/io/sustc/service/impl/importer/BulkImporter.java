package io.sustc.service.impl.importer;

import io.sustc.dto.DanmuRecord;
import io.sustc.dto.UserRecord;
import io.sustc.dto.VideoRecord;
import io.sustc.service.impl.importer.ParallelTasks.NamedTask;
import io.sustc.service.impl.support.Strings;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 *   <li>The indexes, primary/unique constraints and foreign keys of all tables are read from the
 *       catalog and dropped ({@link DeferredSchemaObjects}).
 *   <li>Every table is loaded on its own connection with {@code TRUNCATE} + {@code COPY ...
 *       FREEZE} in one transaction. Frozen rows on all-visible pages mean index-only scans work
 *       right away, without a VACUUM pass to set the visibility map.
 *   <li>The indexes are rebuilt in parallel, largest table first, and the constraints are
 *       re-attached to them. Foreign keys are added {@code NOT VALID} and then validated, one
 *       table per task.
 *   <li>{@code VACUUM (ANALYZE)} gives the planner statistics, and the identity sequences are moved
 *       past the imported ids.
 * </ol>
 *
 * All steps run on a pool of {@code sustc.import.parallelism} threads (default 4), each with its
 * own connection, so the connection pool must allow at least that many connections. If any step
 * fails, the tables are truncated and the schema objects restored before the exception is
 * rethrown, so the database is left empty with its complete schema.
 */
@Component
@Slf4j
public class BulkImporter {

    /** Every table that the import fills. */
    static final List<String> TABLES = List.of(
            "users", "followings", "videos", "viewers", "videolikes", "coins", "favorites", "danmus", "danmulikes");

    private static final String IDENTITY_COLUMNS =
            """
            select c.relname as table_name, a.attname as column_name
            from pg_attribute a
            join pg_class c on c.oid = a.attrelid
            where c.relnamespace = current_schema()::regnamespace and a.attidentity <> ''
              and c.relname = any (?)
            """;

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final int parallelism;

    public BulkImporter(DataSource dataSource, @Value("${sustc.import.parallelism:4}") int parallelism) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("sustc.import.parallelism must be at least 1");
        }
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.parallelism = parallelism;
    }

    public void importData(List<DanmuRecord> danmus, List<UserRecord> users, List<VideoRecord> videos) {
        requireEmptyTables();
        long start = System.nanoTime();
        DeferredSchemaObjects deferred = DeferredSchemaObjects.capture(jdbc, TABLES);

        Map<String, Long> rowCounts = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, threadFactory());
        try {
            deferred.dropAll(jdbc);
            timed("load", () -> ParallelTasks.runAll(pool, loadTasks(danmus, users, videos, rowCounts)));
            timed("indexes", () -> ParallelTasks.runAll(pool, indexTasks(deferred, rowCounts)));
            timed("constraints", () -> {
                deferred.attachConstraints(jdbc);
                deferred.addForeignKeysNotValid(jdbc);
                ParallelTasks.runAll(pool, validationTasks(deferred));
            });
            timed("vacuum analyze", () -> ParallelTasks.runAll(pool, vacuumAnalyzeTasks(rowCounts)));
            resetIdentitySequences();
        } catch (RuntimeException e) {
            shutdownAndWait(pool);
            restoreEmptySchema(deferred, e);
            throw e;
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
            throw new ImportException("importData expects empty tables; call truncate() first");
        }
    }

    private List<NamedTask> loadTasks(
            List<DanmuRecord> danmus, List<UserRecord> users, List<VideoRecord> videos, Map<String, Long> rowCounts) {
        List<NamedTask> tasks = new ArrayList<>();
        // The biggest table first, so that it is not left running alone at the end.
        tasks.add(load("followings", "follower_mid, followee_mid", rowCounts, w -> {
            for (UserRecord u : users) {
                checkInterrupted();
                for (long followee : orEmpty(u.getFollowing())) {
                    w.value(u.getMid()).value(followee).endRow();
                }
            }
        }));
        tasks.add(load("viewers", "bv, mid, view_time", rowCounts, w -> {
            for (VideoRecord v : videos) {
                checkInterrupted();
                long[] mids = orEmpty(v.getViewerMids());
                float[] times = v.getViewTime() == null ? new float[0] : v.getViewTime();
                for (int i = 0; i < mids.length; i++) {
                    w.value(v.getBv()).value(mids[i]).value(times[i]).endRow();
                }
            }
        }));
        tasks.add(load("videolikes", "bv, mid", rowCounts, w -> writePairs(w, videos, VideoRecord::getLike)));
        tasks.add(load("coins", "bv, mid", rowCounts, w -> writePairs(w, videos, VideoRecord::getCoin)));
        tasks.add(load("favorites", "bv, mid", rowCounts, w -> writePairs(w, videos, VideoRecord::getFavorite)));
        tasks.add(load(
                "users",
                "mid, name, sex, birthday, level, coin, sign, identity, password, qq, wechat",
                rowCounts,
                w -> {
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
                }));
        tasks.add(load(
                "videos",
                "bv, title, owner_mid, owner_name, commit_time, review_time, reviewer, public_time, duration,"
                        + " description",
                rowCounts,
                w -> {
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
                }));
        // Danmu ids are assigned in input order, starting from 1.
        tasks.add(load("danmus", "id, bv, mid, time, content, post_time", rowCounts, w -> {
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
        }));
        tasks.add(load("danmulikes", "danmu_id, mid", rowCounts, w -> {
            long id = 1;
            for (DanmuRecord d : danmus) {
                for (long mid : orEmpty(d.getLikedBy())) {
                    w.value(id).value(mid).endRow();
                }
                id++;
            }
        }));
        return tasks;
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
            for (long mid : orEmpty(mids.apply(v))) {
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

    /** Makes the next generated id follow the largest imported one. */
    private void resetIdentitySequences() {
        jdbc.query(
                IDENTITY_COLUMNS,
                rs -> {
                    String table = DeferredSchemaObjects.quote(rs.getString("table_name"));
                    String column = DeferredSchemaObjects.quote(rs.getString("column_name"));
                    jdbc.queryForObject(
                            "select setval(pg_get_serial_sequence(?, ?), coalesce(max(" + column + "), 0) + 1, false)"
                                    + " from " + table,
                            Long.class,
                            table,
                            rs.getString("column_name"));
                },
                (Object) TABLES.toArray(String[]::new));
    }

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

    private void timed(String phase, Runnable step) {
        long start = System.nanoTime();
        step.run();
        log.info("Import phase '{}' took {} ms", phase, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    private static void shutdownAndWait(ExecutorService pool) {
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(5, TimeUnit.MINUTES)) {
                log.warn("Import tasks did not stop within 5 minutes");
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

    private static long[] orEmpty(long[] values) {
        return values == null ? new long[0] : values;
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
