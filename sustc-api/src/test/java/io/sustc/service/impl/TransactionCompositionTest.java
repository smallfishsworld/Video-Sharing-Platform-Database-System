package io.sustc.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.sustc.dto.PostVideoReq;
import io.sustc.service.UserService;
import io.sustc.service.VideoService;
import io.sustc.service.impl.support.Writes;
import java.sql.Timestamp;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The services are written for autocommit, but a caller such as a REST layer may run several of
 * them in one transaction. A refused request must then return its "not done" result without
 * aborting the caller's transaction, so that the caller's later statements still run.
 */
class TransactionCompositionTest extends IntegrationTest {

    @Autowired
    private VideoService videos;

    @Autowired
    private UserService users;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void transactionTemplate() {
        tx = new TransactionTemplate(transactionManager);
    }

    private PostVideoReq request(String title) {
        return PostVideoReq.builder()
                .title(title)
                .duration((float) Fixtures.DEFAULT_DURATION)
                .publicTime(Timestamp.from(fx.now().plus(Duration.ofDays(1))))
                .build();
    }

    @Test
    void aDuplicateTitleInAnUpdateLeavesTheTransactionUsable() {
        long owner = fx.user("owner");
        long fan = fx.user("fan");
        fx.video(owner, "taken");
        String bv = fx.video(owner, "mine");
        String other = fx.video(fan, "theirs");

        Boolean liked = tx.execute(status -> {
            assertThat(videos.updateVideoInfo(fx.auth(owner), bv, request("taken")))
                    .isFalse();
            return videos.likeVideo(fx.auth(owner), other);
        });

        assertThat(liked).isTrue();
        assertThat(count("select count(*) from videolikes where bv = ? and mid = ?", other, owner))
                .isEqualTo(1);
    }

    @Test
    void aDuplicateTitleInAPostLeavesTheTransactionUsable() {
        long owner = fx.user("owner");
        fx.video(owner, "taken");

        String posted = tx.execute(status -> {
            assertThat(videos.postVideo(fx.auth(owner), request("taken"))).isNull();
            return videos.postVideo(fx.auth(owner), request("fresh"));
        });

        assertThat(posted).isNotNull();
        assertThat(count("select count(*) from videos where owner_mid = ?", owner))
                .isEqualTo(2);
    }

    @Test
    void aRepeatedDonationLeavesTheTransactionUsable() {
        long owner = fx.user("owner");
        long donor = fx.user("donor", 0, 5);
        String bv = fx.video(owner, "popular");
        String other = fx.video(owner, "another");

        Boolean second = tx.execute(status -> {
            assertThat(videos.coinVideo(fx.auth(donor), bv)).isTrue();
            assertThat(videos.coinVideo(fx.auth(donor), bv)).isFalse();
            return videos.coinVideo(fx.auth(donor), other);
        });

        assertThat(second).isTrue();
        assertThat(count("select coin from users where mid = ?", donor)).isEqualTo(3);
        DerivedTables.assertConsistent(jdbc);
    }

    /**
     * A constraint violation that the SQL cannot rule out (a concurrent writer can always cause
     * one) runs under a savepoint inside a transaction: only the failed write is rolled back.
     */
    @Test
    void aViolationInsideATransactionRollsBackOnlyThatWrite() {
        long a = fx.user("a");

        Boolean done = tx.execute(status -> {
            jdbc.update("update users set sign = 'kept' where mid = ?", a);
            Boolean duplicate = Writes.run(
                    jdbc.getDataSource(),
                    () -> jdbc.update("insert into users (mid, name, password) values (?, 'copy', 'x')", a) == 1,
                    false);
            assertThat(duplicate).isFalse();
            return users.follow(fx.auth(a), fx.user("b"));
        });

        assertThat(done).isTrue();
        assertThat(jdbc.queryForObject("select sign from users where mid = ?", String.class, a))
                .isEqualTo("kept");
    }
}
