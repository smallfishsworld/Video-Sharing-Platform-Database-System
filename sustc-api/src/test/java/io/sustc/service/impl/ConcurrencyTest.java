package io.sustc.service.impl;

import static io.sustc.service.impl.Concurrency.concurrently;
import static io.sustc.service.impl.Concurrency.inOrderBehind;
import static org.assertj.core.api.Assertions.assertThat;

import io.sustc.dto.PostVideoReq;
import io.sustc.service.DanmuService;
import io.sustc.service.UserService;
import io.sustc.service.VideoService;
import io.sustc.service.impl.Concurrency.Gate;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Concurrent calls that used to race. Most tests hold row locks in a {@link Gate} so that the
 * calls queue up at a known point and are released together: that makes the interleaving that
 * used to lose an update, or to deadlock, happen on every run instead of occasionally.
 */
class ConcurrencyTest extends IntegrationTest {

    @Autowired
    private VideoService videos;

    @Autowired
    private UserService users;

    @Autowired
    private DanmuService danmus;

    @Autowired
    private DataSource dataSource;

    // Toggles ------------------------------------------------------------------------------------

    /**
     * Two likes by one user, both started before either commits. Without serialization both see
     * "not liked": one inserts, the other's insert does nothing ON CONFLICT and it reports "not
     * liked" while the like stays, so one toggle is lost. The gate holds the video row, which the
     * inserts' foreign-key checks need, so both toggles are in flight when it opens.
     */
    @Test
    void concurrentLikeTogglesByOneUserAreAppliedOneAfterTheOther() throws Exception {
        long owner = fx.user("owner");
        long fan = fx.user("fan");
        String bv = fx.video(owner, "contested");

        List<Boolean> results;
        try (Gate gate = new Gate(dataSource, jdbc, "select 1 from videos where bv = ? for update", bv)) {
            results = whileGated(gate, 2, () -> videos.likeVideo(fx.auth(fan), bv));
        }

        assertTogglesConsistent(results, count("select count(*) from videolikes where bv = ? and mid = ?", bv, fan));
        DerivedTables.assertConsistent(jdbc);
    }

    @Test
    void concurrentCollectTogglesByOneUserAreAppliedOneAfterTheOther() throws Exception {
        long owner = fx.user("owner");
        long fan = fx.user("fan");
        String bv = fx.video(owner, "contested");

        List<Boolean> results;
        try (Gate gate = new Gate(dataSource, jdbc, "select 1 from videos where bv = ? for update", bv)) {
            results = whileGated(gate, 2, () -> videos.collectVideo(fx.auth(fan), bv));
        }

        assertTogglesConsistent(results, count("select count(*) from favorites where bv = ? and mid = ?", bv, fan));
        DerivedTables.assertConsistent(jdbc);
    }

    @Test
    void concurrentFollowTogglesByOneUserAreAppliedOneAfterTheOther() throws Exception {
        long fan = fx.user("fan");
        long star = fx.user("star");

        List<Boolean> results;
        try (Gate gate = new Gate(dataSource, jdbc, "select 1 from users where mid = ? for update", star)) {
            results = whileGated(gate, 2, () -> users.follow(fx.auth(fan), star));
        }

        assertTogglesConsistent(
                results,
                count("select count(*) from followings where follower_mid = ? and followee_mid = ?", fan, star));
    }

    @Test
    void concurrentDanmuLikeTogglesByOneUserAreAppliedOneAfterTheOther() throws Exception {
        long owner = fx.user("owner");
        long fan = fx.user("fan");
        String bv = fx.video(owner, "with danmus");
        fx.watch(bv, fan, 10);
        long danmu = fx.danmu(bv, owner, 1, "first", fx.now());

        List<Boolean> results;
        try (Gate gate = new Gate(dataSource, jdbc, "select 1 from danmus where id = ? for update", danmu)) {
            results = whileGated(gate, 2, () -> danmus.likeDanmu(fx.auth(fan), danmu));
        }

        assertTogglesConsistent(
                results, count("select count(*) from danmulikes where danmu_id = ? and mid = ?", danmu, fan));
    }

    /**
     * Starting from "off", every toggle that took effect flipped the state, so the toggles that
     * reported "on" outnumber those that reported "off" by exactly the final state.
     */
    private static void assertTogglesConsistent(List<Boolean> results, long finalState) {
        long on = results.stream().filter(b -> b).count();
        long off = results.size() - on;
        assertThat(on - off)
                .as("results %s against final state %d", results, finalState)
                .isEqualTo(finalState);
    }

    /** Many toggles of a few (user, video) pairs at once; the same invariant per pair. */
    @Test
    void manyConcurrentTogglesLoseNoUpdate() throws Exception {
        long owner = fx.user("owner");
        List<Long> fans = List.of(fx.user("fan 1"), fx.user("fan 2"));
        List<String> bvs = List.of(fx.video(owner, "one"), fx.video(owner, "two"));
        int threads = 8;
        int togglesPerThread = 20;

        List<Callable<long[]>> calls = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            long fan = fans.get(t % fans.size());
            String bv = bvs.get((t / fans.size()) % bvs.size());
            calls.add(() -> {
                long net = 0;
                for (int i = 0; i < togglesPerThread; i++) {
                    net += videos.likeVideo(fx.auth(fan), bv) ? 1 : -1;
                }
                return new long[] {fan, bvs.indexOf(bv), net};
            });
        }
        List<long[]> nets = concurrently(calls);

        for (long fan : fans) {
            for (int b = 0; b < bvs.size(); b++) {
                long net = 0;
                for (long[] n : nets) {
                    if (n[0] == fan && n[1] == b) {
                        net += n[2];
                    }
                }
                assertThat(net)
                        .as("net toggles of fan %d on %s", fan, bvs.get(b))
                        .isEqualTo(count("select count(*) from videolikes where bv = ? and mid = ?", bvs.get(b), fan));
            }
        }
        DerivedTables.assertConsistent(jdbc);
    }

    // updateVideoInfo ----------------------------------------------------------------------------

    /**
     * Two edits of one reviewed video. Only the first one replaces a reviewed state; the second
     * must report "not reviewed". Reading the old row without a lock made both report "reviewed".
     */
    @Test
    void concurrentEditsOfOneVideoEachReportTheReviewStateTheyReplaced() throws Exception {
        long owner = fx.user("owner");
        String bv = fx.video(owner, "reviewed");
        Timestamp later = Timestamp.from(fx.now().plus(Duration.ofDays(2)));

        List<Boolean> results;
        try (Gate gate = new Gate(dataSource, jdbc, "select 1 from videos where bv = ? for update", bv)) {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (String title : List.of("edit one", "edit two")) {
                calls.add(() -> videos.updateVideoInfo(
                        fx.auth(owner),
                        bv,
                        PostVideoReq.builder()
                                .title(title)
                                .duration((float) Fixtures.DEFAULT_DURATION)
                                .publicTime(later)
                                .build()));
            }
            results = whileGated(gate, 2, calls);
        }

        assertThat(results).containsExactlyInAnyOrder(true, false);
    }

    // Co-view counts -----------------------------------------------------------------------------

    /**
     * One user starts watching two videos in two transactions at once. Each trigger computes the
     * new pairs from the user's other views; without serialization neither sees the other's view,
     * and the pair of the two new videos is never counted. The gate holds the co-view rows, so
     * both triggers are running when it opens.
     */
    @Test
    void concurrentViewsOfOneUserKeepTheCoviewCountsExact() throws Exception {
        long owner = fx.user("owner");
        long other = fx.user("other");
        long viewer = fx.user("viewer");
        String a = fx.video(owner, "a");
        String b = fx.video(owner, "b");
        String c = fx.video(owner, "c");
        for (String bv : List.of(a, b, c)) {
            fx.watch(bv, other, 5);
        }
        fx.watch(c, viewer, 5);

        try (Gate gate = new Gate(dataSource, jdbc, "select 1 from video_coviews for update")) {
            List<Callable<Integer>> calls = List.of(
                    () -> jdbc.update("insert into viewers (bv, mid, view_time) values (?, ?, 1)", a, viewer),
                    () -> jdbc.update("insert into viewers (bv, mid, view_time) values (?, ?, 1)", b, viewer));
            whileGated(gate, 2, calls);
        }

        assertThat(count("select viewers from video_coviews where bv = ? and other_bv = ?", a, b))
                .isEqualTo(2);
        DerivedTables.assertConsistent(jdbc);
    }

    /** Overlapping users insert and delete views from 8 threads; the derived tables stay exact. */
    @Test
    void concurrentViewChangesKeepTheDerivedTablesExact() throws Exception {
        long owner = fx.user("owner");
        List<Long> viewers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            viewers.add(fx.user("viewer " + i));
        }
        List<String> bvs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            bvs.add(fx.video(owner, "video " + i));
        }
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Callable<Void>> calls = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Random random = new Random(t);
            calls.add(() -> {
                for (int round = 0; round < 15; round++) {
                    barrier.await(Concurrency.CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                    long mid = viewers.get(random.nextInt(viewers.size()));
                    String bv = bvs.get(random.nextInt(bvs.size()));
                    if (random.nextBoolean()) {
                        jdbc.update(
                                "insert into viewers (bv, mid, view_time) values (?, ?, ?) on conflict do nothing",
                                bv,
                                mid,
                                random.nextInt(50));
                    } else {
                        jdbc.update("delete from viewers where bv = ? and mid = ?", bv, mid);
                    }
                }
                return null;
            });
        }
        concurrently(calls);

        DerivedTables.assertConsistent(jdbc);
    }

    // Deadlocks ----------------------------------------------------------------------------------

    /**
     * Two accounts whose interactions cross. Deleting an account cascades table by table, and the
     * cascades' statement triggers run afterwards, one per table, each locking the counters of its
     * own videos in bv order. With "first" and "second" the two tables in cascade order:
     *
     * <ul>
     *   <li>a has first-table rows on v1 and second-table rows on v2 and v3, so a locks v1, then
     *       v2 and v3;
     *   <li>b has first-table rows on v3 and second-table rows on v1, so b locks v3, then v1.
     * </ul>
     *
     * The gate holds v2. a starts first and waits for the gate holding v1; then b starts and waits
     * for a's v1 holding v3. When the gate opens, a asks for b's v3, and the two deadlock on every
     * run. The loser is retried, and both deletions succeed.
     */
    @Test
    void crossingAccountDeletionsBothSucceed() throws Exception {
        long owner = fx.user("owner");
        long a = fx.user("a");
        long b = fx.user("b");
        String v1 = fx.video(owner, "v1");
        String v2 = fx.video(owner, "v2");
        String v3 = fx.video(owner, "v3");
        String first = cascadesFirst("videolikes", "coins");
        String second = first.equals("videolikes") ? "coins" : "videolikes";
        interact(first, v1, a);
        interact(second, v2, a);
        interact(second, v3, a);
        interact(first, v3, b);
        interact(second, v1, b);

        List<Boolean> results;
        try (Gate gate = new Gate(dataSource, jdbc, "select 1 from video_stats where bv = ? for update", v2)) {
            results = inOrderBehind(
                    gate, List.of(() -> users.deleteAccount(fx.auth(a), a), () -> users.deleteAccount(fx.auth(b), b)));
        }

        assertThat(results).containsExactly(true, true);
        assertThat(count("select count(*) from users where mid in (?, ?)", a, b))
                .isZero();
        DerivedTables.assertConsistent(jdbc);
    }

    private void interact(String table, String bv, long mid) {
        jdbc.update("insert into " + table + " (bv, mid) values (?, ?)", bv, mid);
    }

    /**
     * A video and an account that liked it and donated to another video, deleted at once. Whether
     * the two deadlock depends on the order in which the foreign-key cascades run (it changes when
     * the importer re-creates the foreign keys), so the gate opens after a grace period even if
     * only one of them reached it; either way both deletions must succeed.
     */
    @Test
    void deletingAVideoAndOneOfItsLikersAtOnceBothSucceed() throws Exception {
        long owner = fx.user("owner");
        long liker = fx.user("liker");
        String liked = fx.video(owner, "liked");
        String coined = fx.video(owner, "coined");
        fx.like(liked, liker);
        fx.coin(coined, liker);
        fx.watch(liked, liker, 3);
        fx.watch(coined, liker, 3);

        List<Boolean> results;
        try (Gate gate = new Gate(dataSource, jdbc, "select 1 from video_stats where bv = ? for update", coined)) {
            results = whileGatedOrAfterGrace(
                    gate,
                    List.of(
                            () -> users.deleteAccount(fx.auth(liker), liker),
                            () -> videos.deleteVideo(fx.auth(owner), liked)));
        }

        assertThat(results).containsExactly(true, true);
        DerivedTables.assertConsistent(jdbc);
    }

    /**
     * Two co-viewed videos deleted at once cascade into each other's co-view rows. As above, the
     * gate opens after a grace period if only one deletion reached it.
     */
    @Test
    void deletingTwoCoviewedVideosAtOnceBothSucceed() throws Exception {
        long owner = fx.user("owner");
        List<String> bvs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            bvs.add(fx.video(owner, "video " + i));
        }
        for (int u = 0; u < 3; u++) {
            long viewer = fx.user("viewer " + u);
            for (String bv : bvs) {
                fx.watch(bv, viewer, 1);
            }
        }
        String x = bvs.get(0);
        String y = bvs.get(1);

        List<Boolean> results;
        try (Gate gate = new Gate(
                dataSource, jdbc, "select 1 from video_coviews where other_bv = ? and bv <> ? for update", x, y)) {
            results = whileGatedOrAfterGrace(
                    gate,
                    List.of(() -> videos.deleteVideo(fx.auth(owner), x), () -> videos.deleteVideo(fx.auth(owner), y)));
        }

        assertThat(results).containsExactly(true, true);
        DerivedTables.assertConsistent(jdbc);
    }

    // Helpers ------------------------------------------------------------------------------------

    /**
     * Of two tables that reference users, the one that the deletion of a user cascades to first:
     * the foreign-key action triggers on users fire in the order of their names.
     */
    private String cascadesFirst(String one, String other) {
        return jdbc.queryForObject(
                """
                select c.conrelid::regclass::text
                from pg_trigger t
                join pg_constraint c on c.oid = t.tgconstraint
                where t.tgrelid = 'users'::regclass and t.tgisinternal and c.contype = 'f'
                  and t.tgtype & 8 <> 0 and c.conrelid in (?::regclass, ?::regclass)
                order by t.tgname collate "C"
                limit 1
                """,
                String.class,
                one,
                other);
    }

    private static <T> List<T> whileGated(Gate gate, int copies, Callable<T> call) throws Exception {
        List<Callable<T>> calls = new ArrayList<>();
        for (int i = 0; i < copies; i++) {
            calls.add(call);
        }
        return whileGated(gate, copies, calls);
    }

    /** Starts the calls, opens the gate once {@code waiting} of them queue behind it. */
    private static <T> List<T> whileGated(Gate gate, int waiting, List<Callable<T>> calls) throws Exception {
        return whileGated(calls, () -> gate.releaseWhenWaiting(waiting));
    }

    /** Starts the calls, opens the gate once all of them queue behind it or after 3 seconds. */
    private static <T> List<T> whileGatedOrAfterGrace(Gate gate, List<Callable<T>> calls) throws Exception {
        return whileGated(calls, () -> gate.releaseWhenWaitingOrAfter(calls.size(), Duration.ofSeconds(3)));
    }

    @FunctionalInterface
    private interface Opener {
        void open() throws Exception;
    }

    private static <T> List<T> whileGated(List<Callable<T>> calls, Opener opening) throws Exception {
        List<Callable<T>> withOpener = new ArrayList<>(calls);
        List<T> results = new ArrayList<>();
        Thread opener = new Thread(() -> {
            try {
                opening.open();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        opener.start();
        try {
            results.addAll(concurrently(withOpener));
        } finally {
            opener.join(Concurrency.CALL_TIMEOUT.toMillis());
        }
        return results;
    }
}
