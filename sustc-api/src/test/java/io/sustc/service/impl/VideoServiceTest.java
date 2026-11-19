package io.sustc.service.impl;

import static io.sustc.service.impl.Concurrency.concurrently;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.sustc.dto.AuthInfo;
import io.sustc.dto.PostVideoReq;
import io.sustc.service.VideoService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class VideoServiceTest extends IntegrationTest {

    @Autowired
    private VideoService videos;

    private PostVideoReq.PostVideoReqBuilder request(String title) {
        return PostVideoReq.builder()
                .title(title)
                .description("about " + title)
                .duration(42.5f)
                .publicTime(Timestamp.from(fx.now().plus(Duration.ofDays(1))));
    }

    // postVideo ----------------------------------------------------------------------------------

    @Test
    void postVideoCreatesAnUnreviewedVideoOwnedByTheCaller() {
        long alice = fx.user("alice");

        String bv = videos.postVideo(fx.auth(alice), request("first").build());

        assertThat(bv).startsWith("BV").hasSize(12);
        Map<String, Object> row = jdbc.queryForMap(
                "select owner_mid, owner_name, title, duration, review_time, commit_time from videos where bv = ?", bv);
        assertThat(row)
                .containsEntry("owner_mid", alice)
                .containsEntry("owner_name", "alice")
                .containsEntry("title", "first")
                .containsEntry("duration", 42.5d);
        assertThat(row.get("review_time")).isNull();
        assertThat(((Timestamp) row.get("commit_time")).toInstant()).isCloseTo(fx.now(), within(1, ChronoUnit.MINUTES));
        assertThat(count("select viewers from video_stats where bv = ?", bv)).isZero();
    }

    @Test
    void postVideoRejectsInvalidRequests() {
        long alice = fx.user("alice");
        AuthInfo auth = fx.auth(alice);
        assertThat(videos.postVideo(auth, request("taken").build())).isNotNull();

        assertThat(videos.postVideo(auth, null)).isNull();
        assertThat(videos.postVideo(auth, request(null).build())).isNull();
        assertThat(videos.postVideo(auth, request("").build())).isNull();
        assertThat(videos.postVideo(auth, request("short").duration(9.99f).build()))
                .isNull();
        assertThat(videos.postVideo(
                        auth, request("no public time").publicTime(null).build()))
                .isNull();
        assertThat(videos.postVideo(
                        auth,
                        request("in the past")
                                .publicTime(Timestamp.from(fx.now().minus(Duration.ofMinutes(5))))
                                .build()))
                .isNull();
        assertThat(videos.postVideo(auth, request("taken").build())).isNull();
        assertThat(videos.postVideo(
                        new AuthInfo(alice, "wrong", null, null), request("x").build()))
                .isNull();
        assertThat(count("select count(*) from videos")).isEqualTo(1);
    }

    @Test
    void differentOwnersMayUseTheSameTitle() {
        long alice = fx.user("alice");
        long bob = fx.user("bob");
        assertThat(videos.postVideo(fx.auth(alice), request("same").build())).isNotNull();
        assertThat(videos.postVideo(fx.auth(bob), request("same").duration(10).build()))
                .isNotNull();
    }

    // deleteVideo --------------------------------------------------------------------------------

    @Test
    void ownersAndSuperusersCanDeleteVideosOthersCannot() {
        long owner = fx.user("owner");
        long other = fx.user("other");
        long admin = fx.superuser("admin");
        String first = fx.video(owner, "first");
        String second = fx.video(owner, "second");

        assertThat(videos.deleteVideo(fx.auth(other), first)).isFalse();
        assertThat(videos.deleteVideo(fx.auth(owner), first)).isTrue();
        assertThat(videos.deleteVideo(fx.auth(owner), first)).isFalse();
        assertThat(videos.deleteVideo(fx.auth(admin), second)).isTrue();
        assertThat(videos.deleteVideo(fx.auth(admin), "BVmissing")).isFalse();
        assertThat(videos.deleteVideo(null, second)).isFalse();
        assertThat(count("select count(*) from videos")).isZero();
    }

    @Test
    void deleteVideoRemovesEveryInteractionWithIt() {
        long owner = fx.user("owner");
        long fan = fx.user("fan");
        String doomed = fx.video(owner, "doomed");
        String kept = fx.video(owner, "kept");
        for (String bv : List.of(doomed, kept)) {
            fx.watch(bv, fan, 10);
            fx.like(bv, fan);
            fx.coin(bv, fan);
            fx.favorite(bv, fan);
            fx.likeDanmu(fx.danmu(bv, fan, 1, "wow", fx.now()), owner);
        }

        assertThat(videos.deleteVideo(fx.auth(owner), doomed)).isTrue();

        for (String table : new String[] {"viewers", "videolikes", "coins", "favorites", "danmus", "video_stats"}) {
            assertThat(count("select count(*) from " + table + " where bv = ?", doomed))
                    .as(table)
                    .isZero();
            assertThat(count("select count(*) from " + table + " where bv = ?", kept))
                    .as(table)
                    .isEqualTo(1);
        }
        assertThat(count("select count(*) from danmulikes")).isEqualTo(1);
        assertThat(count("select count(*) from video_coviews")).isZero();
        DerivedTables.assertConsistent(jdbc);
    }

    /** Donations are not refunded when the video goes: the donor's balance stays where it was. */
    @Test
    void deletingADonatedVideoDoesNotRefundTheDonor() {
        long owner = fx.user("owner");
        long donor = fx.user("donor", 0, 3);
        String bv = fx.video(owner, "donated");

        assertThat(videos.coinVideo(fx.auth(donor), bv)).isTrue();
        assertThat(videos.deleteVideo(fx.auth(owner), bv)).isTrue();

        assertThat(count("select coin from users where mid = ?", donor)).isEqualTo(2);
        assertThat(count("select count(*) from coins")).isZero();
        DerivedTables.assertConsistent(jdbc);
    }

    // updateVideoInfo ----------------------------------------------------------------------------

    @Test
    void updatingAReviewedVideoSendsItBackForReview() {
        long owner = fx.user("owner");
        String bv = fx.video(owner, "old title", "old", 42.5, true, fx.now().minus(Duration.ofDays(1)));

        assertThat(videos.updateVideoInfo(
                        fx.auth(owner), bv, request("new title").build()))
                .isTrue();

        Map<String, Object> row = jdbc.queryForMap("select title, review_time, reviewer from videos where bv = ?", bv);
        assertThat(row).containsEntry("title", "new title");
        assertThat(row.get("review_time")).isNull();
        // A second edit of the now unreviewed video is accepted but needs no new review.
        assertThat(videos.updateVideoInfo(
                        fx.auth(owner), bv, request("newer title").build()))
                .isFalse();
        assertThat(jdbc.queryForObject("select title from videos where bv = ?", String.class, bv))
                .isEqualTo("newer title");
    }

    @Test
    void updateVideoInfoRejectsInvalidChanges() {
        long owner = fx.user("owner");
        long other = fx.user("other");
        PostVideoReq current = request("current").build();
        String bv = fx.video(
                owner,
                "current",
                current.getDescription(),
                current.getDuration(),
                true,
                current.getPublicTime().toInstant());
        fx.video(owner, "taken");

        assertThat(videos.updateVideoInfo(fx.auth(owner), bv, current))
                .as("unchanged")
                .isFalse();
        assertThat(videos.updateVideoInfo(
                        fx.auth(owner), bv, request("x").duration(43).build()))
                .as("duration changed")
                .isFalse();
        assertThat(videos.updateVideoInfo(fx.auth(other), bv, request("x").build()))
                .as("not the owner")
                .isFalse();
        assertThat(videos.updateVideoInfo(fx.auth(owner), bv, request("taken").build()))
                .as("title of another video of the owner")
                .isFalse();
        assertThat(videos.updateVideoInfo(fx.auth(owner), bv, request("").build()))
                .as("invalid request")
                .isFalse();
        assertThat(videos.updateVideoInfo(
                        AuthInfo.builder().mid(owner).password("wrong").build(),
                        bv,
                        request("x").build()))
                .as("wrong password")
                .isFalse();
        assertThat(videos.updateVideoInfo(
                        fx.auth(owner),
                        bv,
                        request("x")
                                .publicTime(Timestamp.from(fx.now().minus(Duration.ofMinutes(1))))
                                .build()))
                .as("public time in the past")
                .isFalse();
        assertThat(videos.updateVideoInfo(
                        fx.auth(owner), "BVmissing", request("x").build()))
                .isFalse();
        assertThat(jdbc.queryForObject("select title from videos where bv = ?", String.class, bv))
                .isEqualTo("current");
        assertThat(count("select count(*) from videos where review_time is null"))
                .isZero();
    }

    // searchVideo --------------------------------------------------------------------------------

    @Test
    void searchCountsOccurrencesAsTheSpecificationDescribes() {
        long john = fx.user("John Doe");
        long viewer = fx.user("viewer");
        String overlapping = fx.video(john, "111", null, 100, true, fx.now().minusSeconds(60));
        String twoKeywords = fx.video(john, "1122", null, 100, true, fx.now().minusSeconds(60));
        String java = fx.video(
                john,
                "Java Tutorial",
                "Basic to Advanced Java",
                100,
                true,
                fx.now().minusSeconds(60));

        // "1122" matches "11" once and "12" once; "111" matches "11" once (no overlaps).
        assertThat(videos.searchVideo(fx.auth(viewer), "11 12", 10, 1)).containsExactly(twoKeywords, overlapping);
        // one occurrence in the title and two in the description
        assertThat(videos.searchVideo(fx.auth(viewer), "Java Advanced", 10, 1)).containsExactly(java);
        // case-insensitive, owner names included, runs of spaces ignored
        assertThat(videos.searchVideo(fx.auth(viewer), "  jOHN   ", 10, 1))
                .containsExactlyInAnyOrder(overlapping, twoKeywords, java);
        assertThat(videos.searchVideo(fx.auth(viewer), "nothing", 10, 1)).isEmpty();
    }

    /**
     * The benchmark database has LC_CTYPE=C, where lower() folds only ASCII; the integration
     * tests run with locale C as well, so this fails if the fold depends on the locale.
     */
    @Test
    void searchFoldsCaseBeyondAscii() {
        long owner = fx.user("owner");
        long viewer = fx.user("viewer");
        String elan = fx.video(owner, "Élan vital");
        String strasse = fx.video(owner, "STRASSE und ΣΟΦΙΑ");

        assertThat(videos.searchVideo(fx.auth(viewer), "élan", 10, 1)).containsExactly(elan);
        assertThat(videos.searchVideo(fx.auth(viewer), "ÉLAN", 10, 1)).containsExactly(elan);
        assertThat(videos.searchVideo(fx.auth(viewer), "σοφια", 10, 1)).containsExactly(strasse);
    }

    @Test
    void searchTreatsPatternCharactersLiterally() {
        long owner = fx.user("owner");
        long viewer = fx.user("viewer");
        String percent = fx.video(owner, "100%_off today");
        String dots = fx.video(owner, "a.b* and a?b");
        fx.video(owner, "100 off");
        fx.video(owner, "axb");

        assertThat(videos.searchVideo(fx.auth(viewer), "100%_off", 10, 1)).containsExactly(percent);
        assertThat(videos.searchVideo(fx.auth(viewer), "a.b*", 10, 1)).containsExactly(dots);
        assertThat(videos.searchVideo(fx.auth(viewer), "%", 10, 1)).containsExactly(percent);
    }

    @Test
    void searchBreaksTiesByViewsThenBv() {
        long owner = fx.user("owner");
        long viewer = fx.user("viewer");
        String a = fx.video(owner, "cat a");
        String b = fx.video(owner, "cat b");
        String c = fx.video(owner, "cat c");
        fx.watch(c, viewer, 1);
        fx.watch(c, owner, 1);
        fx.watch(a, viewer, 1);

        assertThat(videos.searchVideo(fx.auth(viewer), "cat", 10, 1)).containsExactly(c, a, b);
    }

    @Test
    void unpublishedVideosAreVisibleOnlyToTheirOwnerAndSuperusers() {
        long owner = fx.user("owner");
        long other = fx.user("other");
        long admin = fx.superuser("admin");
        String published = fx.video(owner, "dog published");
        String unreviewed = fx.unreviewedVideo(owner, "dog unreviewed");
        String embargoed =
                fx.video(owner, "dog embargoed", null, 100, true, fx.now().plus(Duration.ofDays(1)));
        String openEnded = fx.video(owner, "dog no public time", null, 100, true, null);

        assertThat(videos.searchVideo(fx.auth(other), "dog", 10, 1)).containsExactlyInAnyOrder(published, openEnded);
        assertThat(videos.searchVideo(fx.auth(owner), "dog", 10, 1)).hasSize(4);
        assertThat(videos.searchVideo(fx.auth(admin), "dog", 10, 1)).hasSize(4);
        // the same rule guards the interactions
        assertThat(videos.likeVideo(fx.auth(other), unreviewed)).isFalse();
        assertThat(videos.collectVideo(fx.auth(other), embargoed)).isFalse();
        assertThat(videos.likeVideo(fx.auth(admin), unreviewed)).isTrue();
    }

    @Test
    void searchPagesAndValidatesItsArguments() {
        long owner = fx.user("owner");
        AuthInfo auth = fx.auth(owner);
        List<String> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            all.add(fx.video(owner, "page " + i));
        }

        assertThat(videos.searchVideo(auth, "page", 2, 1)).containsExactlyElementsOf(all.subList(0, 2));
        assertThat(videos.searchVideo(auth, "page", 2, 3)).containsExactlyElementsOf(all.subList(4, 5));
        assertThat(videos.searchVideo(auth, "page", 2, 4)).isEmpty();
        assertThat(videos.searchVideo(auth, "   ", 2, 1)).isEmpty();
        assertThat(videos.searchVideo(auth, null, 2, 1)).isNull();
        assertThat(videos.searchVideo(auth, "", 2, 1)).isNull();
        assertThat(videos.searchVideo(auth, "page", 0, 1)).isNull();
        assertThat(videos.searchVideo(auth, "page", 2, 0)).isNull();
        assertThat(videos.searchVideo(new AuthInfo(owner, "wrong", null, null), "page", 2, 1))
                .isNull();
    }

    // getAverageViewRate / getHotspot ------------------------------------------------------------

    @Test
    void averageViewRateIsTheMeanViewTimeOverTheDuration() {
        long owner = fx.user("owner");
        long a = fx.user("a");
        long b = fx.user("b");
        String watched = fx.video(owner, "watched", null, 80, true, null);
        String unwatched = fx.video(owner, "unwatched");
        fx.watch(watched, a, 20);
        fx.watch(watched, b, 60);

        assertThat(videos.getAverageViewRate(watched)).isCloseTo(0.5, within(1e-12));
        assertThat(videos.getAverageViewRate(unwatched)).isEqualTo(-1);
        assertThat(videos.getAverageViewRate("BVmissing")).isEqualTo(-1);
    }

    @Test
    void hotspotsAreTheTenSecondChunksWithTheMostDanmus() {
        long owner = fx.user("owner");
        String bv = fx.video(owner, "busy");
        String quiet = fx.video(owner, "quiet");
        Instant now = fx.now();
        for (double t : new double[] {0, 9.99, 10, 19, 35, 39.5, 99}) {
            fx.danmu(bv, owner, t, "d", now);
        }

        assertThat(videos.getHotspot(bv)).containsExactlyInAnyOrder(0, 1, 3);
        assertThat(videos.getHotspot(quiet)).isEmpty();
        assertThat(videos.getHotspot("BVmissing")).isEmpty();
    }

    // reviewVideo --------------------------------------------------------------------------------

    @Test
    void onlySuperusersReviewAndOnlyOnceAndNeverTheirOwnVideos() {
        long owner = fx.user("owner");
        long admin = fx.superuser("admin");
        String bv = fx.unreviewedVideo(owner, "pending");
        String adminsOwn = fx.unreviewedVideo(admin, "admin's own");

        assertThat(videos.reviewVideo(fx.auth(owner), bv)).isFalse();
        assertThat(videos.reviewVideo(fx.auth(admin), adminsOwn)).isFalse();
        assertThat(videos.reviewVideo(fx.auth(admin), bv)).isTrue();
        assertThat(videos.reviewVideo(fx.auth(admin), bv)).isFalse();
        assertThat(videos.reviewVideo(fx.auth(admin), "BVmissing")).isFalse();
        assertThat(jdbc.queryForObject("select reviewer from videos where bv = ?", Long.class, bv))
                .isEqualTo(admin);
    }

    // coinVideo ----------------------------------------------------------------------------------

    @Test
    void coinVideoChargesOneCoinOncePerVideo() {
        long owner = fx.user("owner");
        long donor = fx.user("donor", 0, 2);
        long broke = fx.user("broke", 0, 0);
        String bv = fx.video(owner, "worth it");

        assertThat(videos.coinVideo(fx.auth(donor), bv)).isTrue();
        assertThat(videos.coinVideo(fx.auth(donor), bv)).isFalse();
        assertThat(videos.coinVideo(fx.auth(broke), bv)).isFalse();
        assertThat(videos.coinVideo(fx.auth(owner), bv)).isFalse();
        assertThat(videos.coinVideo(fx.auth(donor), "BVmissing")).isFalse();
        assertThat(coins(donor)).isEqualTo(1);
        assertThat(coins(owner)).isZero();
        assertThat(count("select count(*) from coins")).isEqualTo(1);
        assertThat(count("select coins from video_stats where bv = ?", bv)).isEqualTo(1);
    }

    @Test
    void coinsCannotBeDonatedToVideosTheDonorCannotSee() {
        long owner = fx.user("owner");
        long donor = fx.user("donor", 0, 5);
        long admin = fx.user("admin-to-be", 0, 5);
        jdbc.update("update users set identity = 'SUPERUSER' where mid = ?", admin);
        String hidden = fx.unreviewedVideo(owner, "hidden");

        assertThat(videos.coinVideo(fx.auth(donor), hidden)).isFalse();
        assertThat(videos.coinVideo(fx.auth(admin), hidden)).isTrue();
        assertThat(coins(donor)).isEqualTo(5);
    }

    @Test
    void concurrentDonationsByOneUserToOneVideoChargeExactlyOnce() throws Exception {
        long owner = fx.user("owner");
        long donor = fx.user("donor", 0, 10);
        String bv = fx.video(owner, "popular");

        List<Boolean> results = concurrently(8, () -> videos.coinVideo(fx.auth(donor), bv));

        assertThat(results).containsOnlyOnce(true);
        assertThat(coins(donor)).isEqualTo(9);
        assertThat(count("select count(*) from coins")).isEqualTo(1);
    }

    @Test
    void concurrentDonationsCannotSpendMoreCoinsThanTheUserHas() throws Exception {
        long owner = fx.user("owner");
        long donor = fx.user("donor", 0, 1);
        List<String> bvs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            bvs.add(fx.video(owner, "video " + i));
        }

        List<Callable<Boolean>> calls = new ArrayList<>();
        for (String bv : bvs) {
            calls.add(() -> videos.coinVideo(fx.auth(donor), bv));
        }
        List<Boolean> results = concurrently(calls);

        assertThat(results).containsOnlyOnce(true);
        assertThat(coins(donor)).isZero();
        assertThat(count("select count(*) from coins")).isEqualTo(1);
        DerivedTables.assertConsistent(jdbc);
    }

    // likeVideo / collectVideo -------------------------------------------------------------------

    @Test
    void likeAndCollectToggleAndReturnTheNewState() {
        long owner = fx.user("owner");
        long fan = fx.user("fan");
        String bv = fx.video(owner, "toggle");

        assertThat(videos.likeVideo(fx.auth(fan), bv)).isTrue();
        assertThat(videos.collectVideo(fx.auth(fan), bv)).isTrue();
        assertThat(count("select likes + favorites from video_stats where bv = ?", bv))
                .isEqualTo(2);
        assertThat(videos.likeVideo(fx.auth(fan), bv)).isFalse();
        assertThat(videos.collectVideo(fx.auth(fan), bv)).isFalse();
        assertThat(count("select count(*) from videolikes")).isZero();
        assertThat(count("select count(*) from favorites")).isZero();
        assertThat(videos.likeVideo(fx.auth(fan), bv)).isTrue();
        DerivedTables.assertConsistent(jdbc);
    }

    @Test
    void ownersCannotLikeOrCollectTheirOwnVideosAndMissingVideosFail() {
        long owner = fx.user("owner");
        String bv = fx.video(owner, "mine");

        assertThat(videos.likeVideo(fx.auth(owner), bv)).isFalse();
        assertThat(videos.collectVideo(fx.auth(owner), bv)).isFalse();
        assertThat(videos.likeVideo(fx.auth(owner), "BVmissing")).isFalse();
        assertThat(videos.collectVideo(null, bv)).isFalse();
        assertThat(count("select count(*) from videolikes")).isZero();
    }

    private int coins(long mid) {
        Integer coin = jdbc.queryForObject("select coin from users where mid = ?", Integer.class, mid);
        return coin == null ? -1 : coin;
    }
}
