package io.sustc.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.sustc.TestApplication;
import io.sustc.service.DatabaseService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class of the integration tests: one PostgreSQL 16 container and one Spring context for the
 * whole test run, the real schema scripts from {@code sql/}, an empty database before every test,
 * and a check after every test that all pooled connections were returned.
 */
@SpringBootTest(classes = TestApplication.class)
abstract class IntegrationTest {

    /**
     * Initialized with locale C, like the measured benchmark database, so that locale-dependent
     * behaviour (case folding, collation) shows up here as it does there. Durability is
     * irrelevant for a throwaway test database; the three settings only speed it up.
     */
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
                    DockerImageName.parse("postgres:16-alpine"))
            .withEnv("POSTGRES_INITDB_ARGS", "--locale=C --encoding=UTF8")
            .withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off", "-c", "full_page_writes=off");

    private static boolean schemaCreated;

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void dataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected DatabaseService databaseService;

    @Autowired
    protected Clock clock;

    @Autowired
    private DataSource dataSource;

    protected Fixtures fx;

    @BeforeEach
    void emptyDatabase() {
        createSchemaOnce(jdbc);
        databaseService.truncate();
        fx = new Fixtures(jdbc, clock);
    }

    /** Every code path, including the failing ones, must give its connections back to the pool. */
    @AfterEach
    void noConnectionIsLeaked() throws SQLException {
        assertThat(dataSource
                        .unwrap(HikariDataSource.class)
                        .getHikariPoolMXBean()
                        .getActiveConnections())
                .as("connections still borrowed from the pool after the test")
                .isZero();
    }

    private static synchronized void createSchemaOnce(JdbcTemplate jdbc) {
        if (!schemaCreated) {
            jdbc.execute(sqlScript("build.sql"));
            jdbc.execute(sqlScript("audit.sql"));
            schemaCreated = true;
        }
    }

    static String sqlScript(String name) {
        String dir = System.getProperty("sustc.sql.dir", "../sql");
        try {
            return Files.readString(Path.of(dir, name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
