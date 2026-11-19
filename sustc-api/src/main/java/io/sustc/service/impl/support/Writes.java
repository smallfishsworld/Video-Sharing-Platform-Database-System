package io.sustc.service.impl.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
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
