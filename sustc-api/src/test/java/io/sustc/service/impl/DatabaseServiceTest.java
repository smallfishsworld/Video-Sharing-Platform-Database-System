package io.sustc.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DatabaseServiceTest extends IntegrationTest {

    @Test
    void truncateEmptiesEveryTableAndRestartsTheIds() {
        long owner = fx.user("owner");
        long fan = fx.user("fan");
        String bv = fx.video(owner, "v");
        fx.watch(bv, fan, 1);
        fx.likeDanmu(fx.danmu(bv, fan, 1, "x", fx.now()), owner);
        fx.follow(fan, owner);

        databaseService.truncate();

        List<String> tables = jdbc.queryForList(
                "select tablename from pg_tables where schemaname = current_schema() order by 1", String.class);
        assertThat(tables).contains("users", "danmulikes", "video_stats", "video_coviews", "log_users");
        for (String table : tables) {
            assertThat(count("select count(*) from " + table)).as(table).isZero();
        }
        assertThat(fx.user("first again")).isEqualTo(1);
    }

    @Test
    void sumAddsInTheDatabase() {
        assertThat(databaseService.sum(2, 3)).isEqualTo(5);
        assertThat(databaseService.sum(-7, 7)).isZero();
    }

    @Test
    void groupMembersAreListed() {
        assertThat(databaseService.getGroupMembers()).isNotEmpty();
    }
}
