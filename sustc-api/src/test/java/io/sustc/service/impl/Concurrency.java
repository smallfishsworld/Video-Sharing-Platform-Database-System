package io.sustc.service.impl;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Helpers for tests that run service calls at the same time. */
final class Concurrency {

    /** No call in these tests may take longer, even one that waits for a deadlock check. */
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    private Concurrency() {}

    static <T> List<T> concurrently(int copies, Callable<T> call) throws Exception {
        List<Callable<T>> calls = new ArrayList<>();
        for (int i = 0; i < copies; i++) {
            calls.add(call);
        }
        return concurrently(calls);
    }

    /** Runs the calls on separate threads, released at the same moment; fails after a timeout. */
    static <T> List<T> concurrently(List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Starts the calls one at a time, each once all earlier ones wait for a lock, then opens the
     * gate: for scenarios that need the calls to reach their locks in a fixed order.
     */
    static <T> List<T> inOrderBehind(Gate gate, List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(call));
                gate.awaitWaiting(futures.size());
            }
            gate.connection.commit();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A transaction on its own connection that holds row locks, so that concurrent calls queue up
     * behind it at a known point. {@link #releaseWhenWaiting} lets them go once the expected
     * number of other sessions are waiting for a lock.
     */
    static final class Gate implements AutoCloseable {

        private final Connection connection;
        private final JdbcTemplate monitor;

        /** Opens the gate's transaction and runs {@code lockingQuery} (a SELECT ... FOR UPDATE). */
        Gate(DataSource dataSource, JdbcTemplate monitor, String lockingQuery, Object... args) throws SQLException {
            this.monitor = monitor;
            this.connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            try (var st = connection.prepareStatement(lockingQuery)) {
                for (int i = 0; i < args.length; i++) {
                    st.setObject(i + 1, args[i]);
                }
                st.executeQuery().close();
            }
        }

        /** Waits until {@code sessions} other backends wait for a lock, then commits. */
        void releaseWhenWaiting(int sessions) throws Exception {
            awaitWaiting(sessions);
            connection.commit();
        }

        /** Waits until {@code sessions} other backends wait for a lock (any lock, not only ours). */
        void awaitWaiting(int sessions) throws Exception {
            long deadline = System.nanoTime() + CALL_TIMEOUT.toNanos();
            while (waiting() < sessions) {
                if (System.nanoTime() > deadline) {
                    throw new TimeoutException("fewer than " + sessions + " sessions queued behind the gate");
                }
                Thread.sleep(10);
            }
        }

        /**
         * Like {@link #releaseWhenWaiting}, but commits after {@code grace} even if fewer sessions
         * are waiting: for calls whose lock order depends on the order of the foreign-key cascades,
         * one of which may finish without ever reaching the gate.
         */
        void releaseWhenWaitingOrAfter(int sessions, Duration grace) throws Exception {
            long deadline = System.nanoTime() + grace.toNanos();
            while (waiting() < sessions && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            connection.commit();
        }

        private long waiting() {
            Long n = monitor.queryForObject(
                    "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()",
                    Long.class);
            return n == null ? 0 : n;
        }

        @Override
        public void close() throws SQLException {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }
}
