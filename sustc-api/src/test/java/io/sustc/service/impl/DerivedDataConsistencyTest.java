package io.sustc.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.sustc.dto.PostVideoReq;
import io.sustc.service.DanmuService;
import io.sustc.service.UserService;
import io.sustc.service.VideoService;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drives the trigger-maintained tables (video_stats, video_coviews) through a random mix of
 * service calls and multi-row SQL statements and compares them with a recomputation from scratch.
 */
class DerivedDataConsistencyTest extends IntegrationTest {

    private static final int USERS = 25;
    private static final int VIDEOS = 10;
    private static final int STEPS = 400;
    private static final int CHECK_EVERY = 50;

    @Autowired
    private VideoService videoService;

    @Autowired
    private UserService userService;

    @Autowired
    private DanmuService danmuService;

    @Autowired
    private TransactionTemplate transactions;

    private Random random;
    private final List<Long> users = new ArrayList<>();
    private final List<String> videos = new ArrayList<>();
    private long admin;

    @ParameterizedTest
    @ValueSource(longs = {1, 20231230, 424242})
    void aRandomWorkloadKeepsTheDerivedTablesConsistent(long seed) {
        random = new Random(seed);
        admin = fx.superuser("admin");
        for (int i = 0; i < USERS; i++) {
            users.add(fx.user("u" + i, random.nextInt(7), 3));
        }
        for (int i = 0; i < VIDEOS; i++) {
            videos.add(fx.video(pick(users), "v" + i, null, 10 + random.nextInt(200), true, null));
        }

        long mostCoviews = 0;
        for (int step = 1; step <= STEPS; step++) {
            randomAction();
            if (step % CHECK_EVERY == 0) {
                DerivedTables.assertConsistent(jdbc);
                mostCoviews = Math.max(mostCoviews, count("select count(*) from video_coviews"));
            }
        }
        // Every kind of action changed something, so none of them was checked vacuously.
        assertThat(effects)
                .containsOnlyKeys(ACTIONS)
                .allSatisfy((action, changes) -> assertThat(changes).as(action).isPositive());
        assertThat(mostCoviews).as("co-view pairs at the checkpoints").isPositive();
    }

    private static final List<String> ACTIONS = List.of(
            "watch several",
            "update view times",
            "unwatch several",
            "unwatch a whole video",
            "like",
            "collect",
            "coin",
            "send danmu",
            "insert danmus",
            "move likes",
            "move danmus",
            "delete favorites",
            "delete danmus",
            "rolled back transaction",
            "post video",
            "delete video",
            "delete account",
            "add user");

    /** Rows changed (or successful calls) per action. */
    private final Map<String, Integer> effects = new TreeMap<>();

    private void record(String action, int changes) {
        effects.merge(action, changes, Integer::sum);
    }

    private void record(String action, boolean changed) {
        record(action, changed ? 1 : 0);
    }

    private void randomAction() {
        int action = random.nextInt(100);
        if (action < 20) {
            record("watch several", watchSeveral());
        } else if (action < 25) {
            record("insert danmus", insertDanmus());
        } else if (action < 33) {
            record(
                    "update view times",
                    jdbc.update(
                            "update viewers set view_time = view_time * 0.5 + ? where mid = ?",
                            random.nextInt(50),
                            pick(users)));
        } else if (action < 43) {
            record("unwatch several", unwatchSeveral());
        } else if (action < 47) {
            record("unwatch a whole video", jdbc.update("delete from viewers where bv = ?", pick(videos)));
        } else if (action < 55) {
            record("like", videoService.likeVideo(fx.auth(pick(users)), pick(videos)));
        } else if (action < 61) {
            record("collect", videoService.collectVideo(fx.auth(pick(users)), pick(videos)));
        } else if (action < 66) {
            record("coin", videoService.coinVideo(fx.auth(pick(users)), pick(videos)));
        } else if (action < 72) {
            long id = danmuService.sendDanmu(
                    fx.auth(pick(users)), pick(videos), "d" + random.nextInt(5), random.nextInt(10));
            record("send danmu", id > 0);
        } else if (action < 77) {
            moveInteractions();
        } else if (action < 82) {
            record("delete favorites", jdbc.update("delete from favorites where mid = ?", pick(users)));
            record(
                    "delete danmus",
                    jdbc.update("delete from danmus where bv = ? and time < ?", pick(videos), random.nextInt(10)));
        } else if (action < 86) {
            rolledBackTransaction();
            record("rolled back transaction", true);
        } else if (action < 90) {
            record("post video", postVideo());
        } else if (action < 93 && videos.size() > 3) {
            String bv = pick(videos);
            assertThat(videoService.deleteVideo(fx.auth(admin), bv)).isTrue();
            videos.remove(bv);
            record("delete video", true);
        } else if (action < 96 && users.size() > 5) {
            long mid = pick(users);
            assertThat(userService.deleteAccount(fx.auth(admin), mid)).isTrue();
            users.remove(mid);
            videos.retainAll(jdbc.queryForList("select bv from videos", String.class));
            record("delete account", true);
        } else {
            users.add(fx.user("late" + users.size(), random.nextInt(7), 3));
            record("add user", true);
        }
    }

    /** One INSERT of several rows: some users watch several videos, some videos get several users. */
    private int watchSeveral() {
        Set<String> rows = new LinkedHashSet<>();
        int n = 1 + random.nextInt(8);
        for (int i = 0; i < n; i++) {
            rows.add(pick(videos) + "," + pick(users));
        }
        List<String> bvs = new ArrayList<>();
        List<Long> mids = new ArrayList<>();
        for (String row : rows) {
            String[] parts = row.split(",");
            bvs.add(parts[0]);
            mids.add(Long.parseLong(parts[1]));
        }
        return jdbc.update(
                "insert into viewers (bv, mid, view_time)"
                        + " select b, m, 1 + (m % 7) * 3.25 from unnest(?::varchar[], ?::bigint[]) as t(b, m)"
                        + " on conflict do nothing",
                bvs.toArray(new String[0]), mids.toArray(new Long[0]));
    }

    /** One INSERT of several danmus, by any users (the service would require them to be viewers). */
    private int insertDanmus() {
        int n = 1 + random.nextInt(4);
        String[] bvs = new String[n];
        Long[] mids = new Long[n];
        for (int i = 0; i < n; i++) {
            bvs[i] = pick(videos);
            mids[i] = pick(users);
        }
        return jdbc.update(
                "insert into danmus (bv, mid, time, content, post_time)"
                        + " select b, m, (m % 10)::double precision, 'bulk', now()"
                        + " from unnest(?::varchar[], ?::bigint[]) as t(b, m)",
                bvs, mids);
    }

    /** One DELETE of several rows, often several videos of the same user (pairs inside the statement). */
    private int unwatchSeveral() {
        long mid = pick(users);
        String[] bvs = {pick(videos), pick(videos), pick(videos)};
        if (random.nextBoolean()) {
            return jdbc.update("delete from viewers where mid = ? and bv = any(?)", mid, bvs);
        }
        return jdbc.update("delete from viewers where mid in (?, ?) and bv = any(?)", mid, pick(users), bvs);
    }

    /** Key updates of the interaction tables move the counters from one video to another. */
    private void moveInteractions() {
        String from = pick(videos);
        String to = pick(videos);
        record(
                "move likes",
                jdbc.update(
                        "update videolikes l set bv = ? where l.bv = ?"
                                + " and not exists (select 1 from videolikes x where x.bv = ? and x.mid = l.mid)",
                        to,
                        from,
                        to));
        record(
                "move danmus",
                jdbc.update("update danmus set bv = ? where bv = ? and time < ?", to, from, random.nextInt(10)));
    }

    /** Writes that are rolled back leave no trace in the derived tables either. */
    private void rolledBackTransaction() {
        String bv = pick(videos);
        long mid = pick(users);
        transactions.executeWithoutResult(status -> {
            jdbc.update("insert into viewers (bv, mid, view_time) values (?, ?, 5) on conflict do nothing", bv, mid);
            jdbc.update("delete from videolikes where bv = ?", bv);
            jdbc.update("insert into coins (bv, mid) values (?, ?) on conflict do nothing", bv, mid);
            status.setRollbackOnly();
        });
    }

    private boolean postVideo() {
        long owner = pick(users);
        String bv = videoService.postVideo(
                fx.auth(owner),
                PostVideoReq.builder()
                        .title("posted " + random.nextInt(1_000_000))
                        .duration(10 + random.nextInt(100))
                        .publicTime(Timestamp.from(fx.now().plus(Duration.ofDays(1))))
                        .build());
        if (bv != null) {
            videos.add(bv);
        }
        return bv != null;
    }

    private <T> T pick(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }

    // -------------------------------------------------------------------------------------------

    @Test
    void theKeyOfAViewersRowCannotBeUpdated() {
        long owner = fx.user("owner");
        long viewer = fx.user("viewer");
        String a = fx.video(owner, "a");
        String b = fx.video(owner, "b");
        fx.watch(a, viewer, 10);
        fx.watch(b, owner, 10);

        assertThatThrownBy(() -> jdbc.update("update viewers set bv = ? where bv = ?", b, a))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot be updated");
        assertThatThrownBy(() -> jdbc.update("update viewers set mid = ? where bv = ?", owner, a))
                .isInstanceOf(DataAccessException.class);
        // Assigning the same key, or changing only the view time, is fine.
        assertThat(jdbc.update("update viewers set bv = bv, view_time = view_time + 1"))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select view_time_sum from video_stats where bv = ?", Double.class, a))
                .isEqualTo(11);
        DerivedTables.assertConsistent(jdbc);
    }

    /**
     * Co-view maintenance needs a new snapshot per statement, so derived_viewers refuses to change
     * views under REPEATABLE READ; READ COMMITTED and SERIALIZABLE are accepted.
     */
    @Test
    void viewChangesAreRefusedUnderRepeatableRead() {
        long owner = fx.user("owner");
        long viewer = fx.user("viewer");
        String bv = fx.video(owner, "v");
        TransactionTemplate repeatableRead = new TransactionTemplate(transactions.getTransactionManager());
        repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        TransactionTemplate serializable = new TransactionTemplate(transactions.getTransactionManager());
        serializable.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);

        assertThatThrownBy(() -> repeatableRead.executeWithoutResult(
                        status -> jdbc.update("insert into viewers (bv, mid, view_time) values (?, ?, 1)", bv, viewer)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("READ COMMITTED");
        assertThat(count("select count(*) from viewers")).isZero();
        serializable.executeWithoutResult(
                status -> jdbc.update("insert into viewers (bv, mid, view_time) values (?, ?, 1)", bv, viewer));
        assertThat(count("select viewers from video_stats where bv = ?", bv)).isEqualTo(1);
        DerivedTables.assertConsistent(jdbc);
    }

    /**
     * A co-view count that has drifted below what a deletion removes is an error, not a row that
     * silently disappears. The drift is made by hand: both viewers of a pair are deleted at once
     * after the count was set from 2 to 1.
     */
    @Test
    void aCoviewCountBelowItsLossFailsTheDeletion() {
        long owner = fx.user("owner");
        String a = fx.video(owner, "a");
        String b = fx.video(owner, "b");
        for (String name : List.of("v1", "v2")) {
            long viewer = fx.user(name);
            fx.watch(a, viewer, 1);
            fx.watch(b, viewer, 1);
        }
        jdbc.update("update video_coviews set viewers = 1");

        assertThatThrownBy(() -> jdbc.update("delete from viewers where bv = ?", a))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("video_coviews_viewers_check");
        assertThat(count("select count(*) from viewers where bv = ?", a)).isEqualTo(2);
    }

    @Test
    void videoStatsRowsFollowTheirVideos() {
        long owner = fx.user("owner");
        String bv = fx.video(owner, "v");
        assertThat(count("select count(*) from video_stats where bv = ?", bv)).isEqualTo(1);
        jdbc.update("delete from videos where bv = ?", bv);
        assertThat(count("select count(*) from video_stats")).isZero();
    }
}
