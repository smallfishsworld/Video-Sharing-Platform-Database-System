package io.sustc.service.impl.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;

class WritesTest {

    private static DataAccessException failure(String sqlState) {
        return new UncategorizedSQLException("test", "select 1", new SQLException("failed", sqlState));
    }

    @Test
    void aWriteThatLosesADeadlockIsRetried() {
        AtomicInteger attempts = new AtomicInteger();

        boolean result = Writes.run(
                null,
                () -> {
                    if (attempts.incrementAndGet() < Writes.MAX_ATTEMPTS) {
                        throw failure("40P01");
                    }
                    return true;
                },
                false);

        assertThat(result).isTrue();
        assertThat(attempts).hasValue(Writes.MAX_ATTEMPTS);
    }

    @Test
    void retriesStopAfterTheLastAttempt() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> Writes.run(
                        null,
                        () -> {
                            attempts.incrementAndGet();
                            throw failure("40001");
                        },
                        false))
                .isInstanceOf(DataAccessException.class);
        assertThat(attempts).hasValue(Writes.MAX_ATTEMPTS);
    }

    @Test
    void uniqueAndForeignKeyViolationsMeanNotDone() {
        assertThat(Writes.run(
                        null,
                        () -> {
                            throw failure("23505");
                        },
                        -1L))
                .isEqualTo(-1L);
        assertThat(Writes.run(
                        null,
                        () -> {
                            throw failure("23503");
                        },
                        -1L))
                .isEqualTo(-1L);
    }

    @Test
    void otherFailuresPropagateWithoutARetry() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> Writes.run(
                        null,
                        () -> {
                            attempts.incrementAndGet();
                            throw failure("23514");
                        },
                        false))
                .isInstanceOf(DataAccessException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void sqlStateIsFoundAnywhereInTheCauseChain() {
        RuntimeException wrapped = new RuntimeException(failure("40P01"));

        assertThat(Writes.sqlState(wrapped)).isEqualTo("40P01");
        assertThat(Writes.sqlState(new RuntimeException("no SQL"))).isNull();
    }
}
