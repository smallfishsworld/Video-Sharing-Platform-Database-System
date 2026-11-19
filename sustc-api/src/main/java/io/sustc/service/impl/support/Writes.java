package io.sustc.service.impl.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs one write of a service method. Every write is a single call that PostgreSQL executes
 * atomically, but a correct request can still fail because of a concurrent write:
 *
 * <ul>
 *   <li>It can lose a deadlock (SQLSTATE 40P01) or a serialization check (40001). The call then
 *       had no effect. Outside a caller's transaction it is retried, up to {@value #MAX_ATTEMPTS}
 *       attempts in all, after a short random pause.
 *   <li>It can hit a unique violation (23505) or a foreign-key violation (23503), because a
 *       concurrent write inserted the same row or deleted a referenced row after the statement
 *       took its snapshot. The method then returns its "not done" result.
 * </ul>
 *
 * <p>Inside a caller's transaction the write runs under a savepoint, so a violation rolls back
 * only the write and the caller's transaction stays usable. A deadlock there aborts the caller's
 * whole transaction, so it is not retried here: the caller must retry its transaction.
 */
@Slf4j
public final class Writes {

    /** Attempts in all for a write that loses a deadlock outside a caller's transaction. */
    public static final int MAX_ATTEMPTS = 3;

    /** The pause before attempt n is uniformly random in [1, n * this) milliseconds. */
    private static final int BACKOFF_MILLIS_PER_ATTEMPT = 10;

    /**
     * The first statement of a call that must not race with the same user's other writes of that
     * kind (the toggles): a transaction-scoped advisory lock keyed on the acting user's mid.
     *
     * <p>Under READ COMMITTED a statement sees what was committed before it started. Two
     * concurrent toggles of one row both start before either commits: one deletes or inserts the
     * row, the other finds nothing to delete, its insert does nothing ON CONFLICT, and it reports
     * a stale state, so one toggle is lost. A lock inside the toggle statement would not help,
     * because that statement's snapshot is older than the wait. The lock is therefore a statement
     * of its own: the toggle that follows starts after the lock is granted and sees every toggle
     * that committed before.
     *
     * <p>Both statements go to the server in one round trip. With autocommit, pgjdbc sends them
     * before a single Sync message, and PostgreSQL runs everything up to a Sync as one implicit
     * transaction, so the lock is held until the toggle commits. Inside a caller's transaction the
     * lock is held until that transaction ends. Advisory locks live in shared memory; taking one
     * writes no WAL and dirties no page. Keys of one-argument advisory locks are user mids.
     */
    public static final String LOCK_ACTING_USER = "select pg_advisory_xact_lock(:me);\n";

    /**
     * The first statement of an account deletion: the deletion lock (two-key advisory lock (1, 0),
     * whose key space is separate from the one-key locks of {@link #LOCK_ACTING_USER}) in
     * exclusive mode, held until the deletion commits.
     *
     * <p>A deletion cascades table by table, and the derived-table trigger of each cascaded table
     * locks the counters of its own videos (sql/build.sql). Two account deletions, or an account
     * deletion and a video deletion, could therefore lock shared counters in crossing orders and
     * deadlock; on the benchmark data, where every account touches about half of the 44 videos,
     * eight concurrent account deletions deadlocked on most calls. With this lock they run one
     * after the other. They would queue on the shared counters anyway, so little concurrency is
     * lost: the counters of a popular video take one deletion at a time.
     */
    public static final String LOCK_DELETIONS_EXCLUSIVE = "select pg_advisory_xact_lock(1, 0);\n";

    /**
     * The first statement of a video deletion: the deletion lock in shared mode. Video deletions
     * run concurrently with each other (they change no counter of another video, and the co-view
     * rows they share are deleted in key order), but not with account deletions.
     */
    public static final String LOCK_DELETIONS_SHARED = "select pg_advisory_xact_lock_shared(1, 0);\n";

    /**
     * Executes a call made of {@link #LOCK_ACTING_USER} and one statement that returns a single
     * boolean, and returns that boolean ({@code false} if the statement returned no row).
     */
    public static final PreparedStatementCallback<Boolean> RESULT_AFTER_LOCK = ps -> {
        ps.execute();
        if (!ps.getMoreResults()) {
            throw new IllegalStateException("The statement after the lock returned no result set");
        }
        try (ResultSet rs = ps.getResultSet()) {
            return rs.next() && rs.getBoolean(1);
        }
    };

    /**
     * Executes a call made of one of the lock statements above and one INSERT, UPDATE or DELETE,
     * and returns that statement's update count.
     */
    public static final PreparedStatementCallback<Integer> UPDATE_COUNT_AFTER_LOCK = ps -> {
        ps.execute();
        if (ps.getMoreResults()) {
            throw new IllegalStateException("The statement after the lock returned a result set");
        }
        return ps.getUpdateCount();
    };

    private static final Set<String> RETRYABLE = Set.of("40P01", "40001");
    private static final Set<String> NOT_DONE = Set.of("23503", "23505");

    private Writes() {}

    /**
     * Runs {@code write} and returns its result, or {@code notDone} if it failed with a unique or
     * foreign-key violation.
     */
    public static <T> T run(DataSource dataSource, Supplier<T> write, T notDone) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            Connection con = DataSourceUtils.getConnection(dataSource);
            try {
                if (DataSourceUtils.isConnectionTransactional(con, dataSource) && !con.getAutoCommit()) {
                    return underSavepoint(con, write, notDone);
                }
            } catch (SQLException e) {
                throw new UncategorizedSQLException("savepoint", null, e);
            } finally {
                DataSourceUtils.releaseConnection(con, dataSource);
            }
        }
        return withRetries(write, notDone);
    }

    private static <T> T withRetries(Supplier<T> write, T notDone) {
        for (int attempt = 1; ; attempt++) {
            try {
                return write.get();
            } catch (DataAccessException e) {
                String state = sqlState(e);
                if (NOT_DONE.contains(state)) {
                    return notDone;
                }
                if (!RETRYABLE.contains(state) || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                log.debug("Write failed with SQLSTATE {} (attempt {} of {}), retrying", state, attempt, MAX_ATTEMPTS);
                pause(attempt, e);
            }
        }
    }

    private static <T> T underSavepoint(Connection con, Supplier<T> write, T notDone) throws SQLException {
        Savepoint savepoint = con.setSavepoint();
        T result;
        try {
            result = write.get();
        } catch (DataAccessException e) {
            if (!NOT_DONE.contains(sqlState(e))) {
                throw e;
            }
            con.rollback(savepoint);
            return notDone;
        }
        con.releaseSavepoint(savepoint);
        return result;
    }

    private static void pause(int attempt, DataAccessException cause) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, (long) attempt * BACKOFF_MILLIS_PER_ATTEMPT));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw cause;
        }
    }

    /** The SQLSTATE of the first {@link SQLException} in the cause chain, or {@code null}. */
    static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
