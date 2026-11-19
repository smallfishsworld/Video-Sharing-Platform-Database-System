package io.sustc.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.sustc.service.RecommenderService;
import io.sustc.service.VideoService;
import io.sustc.service.impl.auth.Authenticator;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The bv tie-breaks must give Java's String order (byte order for ASCII bvs) on any server. The
 * integration tests run on a database with locale C, where the default collation already
 * compares bytes, so they cannot tell. This test builds the schema in a second database of the
 * same container whose default collation is ICU en-US, which sorts {@code BV1a} before
 * {@code BV1A}; without the explicit {@code collate "C"} the services would follow that order.
 */
class CollationIndependenceTest {

    private static final String DATABASE = "sustc_icu_collation";

    private static JdbcTemplate jdbc;
    private static VideoService videos;
    private static RecommenderService recommender;

    private Fixtures fx;

    @BeforeAll
    static void createDatabase() {
        var container = IntegrationTest.POSTGRES;
        JdbcTemplate admin = new JdbcTemplate(
                new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword()));
        admin.execute("drop database if exists " + DATABASE);
        admin.execute("create database " + DATABASE
                + " template template0 encoding 'UTF8' locale 'C' locale_provider icu icu_locale 'en-US'");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://" + container.getHost() + ":" + container.getFirstMappedPort() + "/" + DATABASE,
                container.getUsername(),
                container.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(IntegrationTest.sqlScript("build.sql"));
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(dataSource);
        Authenticator authenticator = new Authenticator(jdbc);
        videos = new VideoServiceImpl(named, authenticator, Clock.systemUTC());
        recommender = new RecommenderServiceImpl(named, authenticator);
    }

    @BeforeEach
    void emptyTables() {
        jdbc.execute("truncate users, videos restart identity cascade");
        fx = new Fixtures(jdbc, Clock.systemUTC());
    }

    @Test
    void theDatabaseReallySortsCaseFirstByLanguage() {
        assertThat(jdbc.queryForList(
                        "select bv from (values ('BV1A00000001'), ('BV1a00000001')) t (bv) order by bv", String.class))
                .containsExactly("BV1a00000001", "BV1A00000001");
    }

    @Test
    void similarVideosBreakTiesInByteOrder() {
        long owner = fx.user("owner");
        String target = fx.videoWithBv("BV1target0001", owner, "target");
        String lower = fx.videoWithBv("BV1a00000001", owner, "lower");
        String upper = fx.videoWithBv("BV1A00000001", owner, "upper");
        long viewer = fx.user("viewer");
        for (String bv : List.of(target, lower, upper)) {
            fx.watch(bv, viewer, 1);
        }

        assertThat(recommender.recommendNextVideo(target)).containsExactly(upper, lower);
    }

    @Test
    void generalRecommendationsBreakTiesInByteOrder() {
        long owner = fx.user("owner");
        String lower = fx.videoWithBv("BV1a00000001", owner, "lower");
        String upper = fx.videoWithBv("BV1A00000001", owner, "upper");

        assertThat(recommender.generalRecommendations(10, 1)).containsExactly(upper, lower);
    }

    @Test
    void searchBreaksTiesInByteOrder() {
        long owner = fx.user("owner");
        String lower = fx.videoWithBv("BV1a00000001", owner, "same title");
        String upper = fx.videoWithBv("BV1A00000001", owner, "same title too");

        assertThat(videos.searchVideo(fx.auth(owner), "same", 10, 1)).containsExactly(upper, lower);
    }

    @Test
    void personalRecommendationsBreakFullTiesInByteOrder() {
        long owner = fx.user("owner");
        long me = fx.user("me");
        long friend = fx.user("friend");
        fx.befriend(me, friend);
        String lower = fx.videoWithBv("BV1a00000001", owner, "lower");
        String upper = fx.videoWithBv("BV1A00000001", owner, "upper");
        jdbc.update("update videos set public_time = (select max(public_time) from videos)");
        fx.watch(lower, friend, 1);
        fx.watch(upper, friend, 1);

        assertThat(recommender.recommendVideosForUser(fx.auth(me), 10, 1)).containsExactly(upper, lower);
    }
}
