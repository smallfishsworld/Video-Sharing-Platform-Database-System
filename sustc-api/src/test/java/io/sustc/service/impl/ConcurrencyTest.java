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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

    @Autowired
    private PlatformTransactionManager transactionManager;

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

    /**
     * The same crossing deletions, each inside a caller's transaction, where a deadlock is not
     * retried (it aborts the caller's transaction): both must succeed, so the deletions must never
     * deadlock in the first place. The deletion lock makes b wait for a's whole transaction.
     */
    @Test
    void crossingAccountDeletionsInCallerTransactionsNeverDeadlock() throws Exception {
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
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        List<Boolean> results;
        try (Gate gate = new Gate(dataSource, jdbc, "select 1 from video_stats where bv = ? for update", v2)) {
            results = inOrderBehind(
                    gate,
                    List.of(
                            () -> tx.execute(status -> users.deleteAccount(fx.auth(a), a)),
                            () -> tx.execute(status -> users.deleteAccount(fx.auth(b), b))));
        }

        assertThat(results).containsExactly(true, true);
        assertThat(count("select count(*) from users where mid in (?, ?)", a, b))
                .isZero();
        DerivedTables.assertConsistent(jdbc);
    }

    /**
     * Two co-viewed videos deleted at once, each inside a caller's transaction. A test trigger
     * makes every co-view row deletion take 200 ms, so both deletions are inside their co-view
     * deletions at the same time. Deleted by two cascades, one per direction, the rows between the
     * two videos were taken in crossing orders and the deletions deadlocked on every run; deleted
     * in key order, the second deletion waits for the first.
     */
    @Test
    void coviewedVideoDeletionsInCallerTransactionsNeverDeadlock() throws Exception {
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
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        List<Boolean> results = withSlowRowDeletes(
                "video_coviews",
                List.of(
                        () -> tx.execute(status -> videos.deleteVideo(fx.auth(owner), bvs.get(0))),
                        () -> tx.execute(status -> videos.deleteVideo(fx.auth(owner), bvs.get(1)))));

        assertThat(results).containsExactly(true, true);
        DerivedTables.assertConsistent(jdbc);
    }

    /**
     * Two freshly registered accounts that follow each other, deleted at once inside callers'
     * transactions. Neither has interactions or videos, but each deletion cascades into both
     * followings rows between them, through the follower key and then through the followee key, so
     * the two cascades take the two rows in crossing orders. A test trigger makes every followings
     * deletion take 200 ms, so both deletions are inside their first cascade at the same time.
     * Without the deletion lock they deadlocked; their follow rows make both take it, and the
     * second deletion waits for the first transaction.
     */
    @Test
    void mutuallyFollowingNewAccountsDeletedInCallerTransactionsNeverDeadlock() throws Exception {
        long a = fx.user("a");
        long b = fx.user("b");
        fx.follow(a, b);
        fx.follow(b, a);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        List<Boolean> results = withSlowRowDeletes(
                "followings",
                List.of(
                        () -> tx.execute(status -> users.deleteAccount(fx.auth(a), a)),
                        () -> tx.execute(status -> users.deleteAccount(fx.auth(b), b))));

        assertThat(results).containsExactly(true, true);
        assertThat(count("select count(*) from users where mid in (?, ?)", a, b))
                .isZero();
        assertThat(count("select count(*) from followings")).isZero();
    }

    /**
     * A view inserted while a video that its viewer watched is being deleted. A test trigger
     * holds the deletion for a second after its co-view rows are gone. The new view's pair
     * statement would still see the viewer's view of the deleted video (that deletion has not
     * committed) and add the pair of the new and the deleted video again, a row that nothing
     * deletes any more. derived_viewers locks the viewer's other videos first, so the insert
     * waits for the deletion and pairs only with the videos that remain.
     */
    @Test
    void aViewInsertedDuringAVideoDeletionAddsNoPairOfTheDeletedVideo() throws Exception {
        long owner = fx.user("owner");
        long viewer = fx.user("viewer");
        String doomed = fx.video(owner, "doomed");
        String kept = fx.video(owner, "kept");
        String watched = fx.video(owner, "watched");
        fx.watch(doomed, viewer, 1);
        fx.watch(kept, viewer, 1);
        jdbc.execute(
                """
                create function test_slow_statement() returns trigger language plpgsql as
                $$ begin perform pg_sleep(1); return null; end $$
                """);
        // Statement triggers fire in name order, so this one runs after derived_videos_delete.
        jdbc.execute("create trigger test_slow_statement after delete on videos"
                + " for each statement execute function test_slow_statement()");
        List<Integer> results;
        try {
            results = concurrently(List.of(() -> videos.deleteVideo(fx.auth(owner), doomed) ? 1 : 0, () -> {
                awaitSleepingSessions(1);
                return jdbc.update("insert into viewers (bv, mid, view_time) values (?, ?, 1)", watched, viewer);
            }));
        } finally {
            jdbc.execute("drop trigger test_slow_statement on videos");
            jdbc.execute("drop function test_slow_statement()");
        }

        assertThat(results).containsExactly(1, 1);
        assertThat(count("select count(*) from video_coviews where ? in (bv, other_bv)", doomed))
                .isZero();
        assertThat(count("select viewers from video_coviews where bv = ? and other_bv = ?", watched, kept))
                .isEqualTo(1);
        DerivedTables.assertConsistent(jdbc);
    }

    /**
     * A deletion that the caller may not make returns false without taking the deletion lock, so it
     * holds up no other deletion; one that the caller may make holds the lock until its
     * transaction ends.
     */
    @Test
    void onlyPermittedDeletionsTakeTheDeletionLock() {
        long owner = fx.user("owner");
        long stranger = fx.user("stranger");
        String bv = fx.video(owner, "v");
        fx.like(fx.video(owner, "kept"), owner);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // Each value is the number of deletion locks held after the call, or -1 if the call
        // returned the other result than expected.
        Long refusedAccount =
                tx.execute(status -> users.deleteAccount(fx.auth(stranger), owner) ? -1L : heldDeletionLocks());
        Long refusedVideo = tx.execute(status -> videos.deleteVideo(fx.auth(stranger), bv) ? -1L : heldDeletionLocks());
        Long deletedVideo = tx.execute(status -> videos.deleteVideo(fx.auth(owner), bv) ? heldDeletionLocks() : -1L);
        Long deletedAccount =
                tx.execute(status -> users.deleteAccount(fx.auth(owner), owner) ? heldDeletionLocks() : -1L);

        assertThat(refusedAccount).isZero();
        assertThat(refusedVideo).isZero();
        assertThat(deletedVideo).isEqualTo(1L);
        assertThat(deletedAccount).isEqualTo(1L);
    }

    /** The deletion locks (advisory lock (1, 0), either mode) held by this transaction's session. */
    private long heldDeletionLocks() {
        return count(
                """
                select count(*) from pg_locks
                where locktype = 'advisory' and pid = pg_backend_pid()
                  and classid = 1 and objid = 0 and objsubid = 2 and granted
                """);
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

    /** Runs the calls at once while a test trigger makes every row deletion in {@code table} take 200 ms. */
    private <T> List<T> withSlowRowDeletes(String table, List<Callable<T>> calls) throws Exception {
        jdbc.execute(
                """
                create function test_slow_delete() returns trigger language plpgsql as
                $$ begin perform pg_sleep(0.2); return old; end $$
                """);
        jdbc.execute("create trigger test_slow_delete before delete on " + table
                + " for each row execute function test_slow_delete()");
        try {
            return concurrently(calls);
        } finally {
            jdbc.execute("drop trigger test_slow_delete on " + table);
            jdbc.execute("drop function test_slow_delete()");
        }
    }

    /** Waits until {@code sessions} backends of this database are inside pg_sleep. */
    private void awaitSleepingSessions(int sessions) throws Exception {
        long deadline = System.nanoTime() + Concurrency.CALL_TIMEOUT.toNanos();
        while (count(
                        "select count(*) from pg_stat_activity where wait_event = 'PgSleep' and datname = current_database()")
                < sessions) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("fewer than " + sessions + " sessions reached pg_sleep");
            }
            Thread.sleep(10);
        }
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
