package io.sustc.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.sustc.dto.AuthInfo;
import io.sustc.service.DanmuService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DanmuServiceTest extends IntegrationTest {

    private static final double DURATION = 60;

    @Autowired
    private DanmuService danmus;

    private long owner;
    private long viewer;
    private String bv;

    private void publishedVideoWithAViewer() {
        owner = fx.user("owner");
        viewer = fx.user("viewer");
        bv = fx.video(owner, "video", null, DURATION, true, fx.now().minus(Duration.ofHours(1)));
        fx.watch(bv, viewer, 30);
    }

    // sendDanmu ----------------------------------------------------------------------------------

    @Test
    void viewersCanSendDanmusToAPublishedVideo() {
        publishedVideoWithAViewer();

        long id = danmus.sendDanmu(fx.auth(viewer), bv, "first!", 1.5f);

        assertThat(id).isPositive();
        Map<String, Object> row =
                jdbc.queryForMap("select bv, mid, time, content, post_time from danmus where id = ?", id);
        assertThat(row)
                .containsEntry("bv", bv)
                .containsEntry("mid", viewer)
                .containsEntry("time", 1.5d)
                .containsEntry("content", "first!");
        assertThat(((Timestamp) row.get("post_time")).toInstant()).isCloseTo(fx.now(), within(1, ChronoUnit.MINUTES));
        assertThat(danmus.sendDanmu(fx.auth(viewer), bv, "second", 0)).isGreaterThan(id);
        assertThat(danmus.sendDanmu(fx.auth(viewer), bv, "at the very end", (float) DURATION))
                .isPositive();
        assertThat(count("select danmus from video_stats where bv = ?", bv)).isEqualTo(3);
    }

    @Test
    void sendDanmuRejectsInvalidRequests() {
        publishedVideoWithAViewer();
        long stranger = fx.user("stranger");
        AuthInfo auth = fx.auth(viewer);

        assertThat(danmus.sendDanmu(fx.auth(stranger), bv, "never watched it", 1))
                .isEqualTo(-1);
        assertThat(danmus.sendDanmu(new AuthInfo(viewer, "wrong", null, null), bv, "x", 1))
                .isEqualTo(-1);
        assertThat(danmus.sendDanmu(null, bv, "x", 1)).isEqualTo(-1);
        assertThat(danmus.sendDanmu(auth, "BVmissing", "x", 1)).isEqualTo(-1);
        assertThat(danmus.sendDanmu(auth, null, "x", 1)).isEqualTo(-1);
        assertThat(danmus.sendDanmu(auth, bv, null, 1)).isEqualTo(-1);
        assertThat(danmus.sendDanmu(auth, bv, "", 1)).isEqualTo(-1);
        assertThat(danmus.sendDanmu(auth, bv, "before the start", -0.5f)).isEqualTo(-1);
        assertThat(danmus.sendDanmu(auth, bv, "after the end", (float) DURATION + 0.5f))
                .isEqualTo(-1);
        assertThat(danmus.sendDanmu(auth, bv, "not a time", Float.NaN)).isEqualTo(-1);
        assertThat(count("select count(*) from danmus")).isZero();
    }

    @Test
    void danmusCannotBeSentToUnpublishedVideosEvenByTheirOwnerOrASuperuser() {
        long owner = fx.user("owner");
        long admin = fx.superuser("admin");
        String unreviewed = fx.unreviewedVideo(owner, "unreviewed");
        String embargoed =
                fx.video(owner, "embargoed", null, DURATION, true, fx.now().plus(Duration.ofDays(1)));
        for (String video : new String[] {unreviewed, embargoed}) {
            fx.watch(video, owner, 1);
            fx.watch(video, admin, 1);
            assertThat(danmus.sendDanmu(fx.auth(owner), video, "x", 1)).isEqualTo(-1);
            assertThat(danmus.sendDanmu(fx.auth(admin), video, "x", 1)).isEqualTo(-1);
        }
    }

    // displayDanmu -------------------------------------------------------------------------------

    @Test
    void displayListsTheDanmusInTheRangeByTime() {
        publishedVideoWithAViewer();
        Instant t = fx.now().minus(Duration.ofMinutes(30));
        long at10 = fx.danmu(bv, viewer, 10, "a", t);
        long at5 = fx.danmu(bv, viewer, 5, "b", t);
        long at20 = fx.danmu(bv, viewer, 20, "c", t);
        long alsoAt10 = fx.danmu(bv, owner, 10, "d", t);
        fx.danmu(bv, viewer, 20.5, "outside", t);
        fx.danmu(bv, viewer, 4.99, "outside", t);

        assertThat(danmus.displayDanmu(bv, 5, 20, false)).containsExactly(at5, at10, alsoAt10, at20);
        assertThat(danmus.displayDanmu(bv, 10, 10, false)).containsExactly(at10, alsoAt10);
        assertThat(danmus.displayDanmu(bv, 30, 60, false)).isEmpty();
    }

    @Test
    void theFilterKeepsTheEarliestPostedDanmuOfEachContent() {
        publishedVideoWithAViewer();
        Instant t = fx.now().minus(Duration.ofHours(1));
        long laterPostedEarlierInTheVideo = fx.danmu(bv, viewer, 1, "same", t.plusSeconds(60));
        long earliestPosted = fx.danmu(bv, owner, 8, "same", t);
        long unique = fx.danmu(bv, viewer, 3, "unique", t.plusSeconds(120));
        long duplicateOutsideTheRange = fx.danmu(bv, viewer, 50, "unique", t);

        assertThat(danmus.displayDanmu(bv, 0, 10, true)).containsExactly(unique, earliestPosted);
        assertThat(danmus.displayDanmu(bv, 0, 10, false))
                .containsExactly(laterPostedEarlierInTheVideo, unique, earliestPosted);
        assertThat(danmus.displayDanmu(bv, 0, 60, true)).containsExactly(earliestPosted, duplicateOutsideTheRange);
    }

    @Test
    void displayReturnsNullForInvalidRangesAndUnpublishedOrMissingVideos() {
        publishedVideoWithAViewer();
        String unreviewed = fx.unreviewedVideo(owner, "unreviewed");
        String embargoed =
                fx.video(owner, "embargoed", null, DURATION, true, fx.now().plus(Duration.ofDays(1)));

        assertThat(danmus.displayDanmu(bv, 10, 5, false)).isNull();
        assertThat(danmus.displayDanmu(bv, -1, 5, false)).isNull();
        assertThat(danmus.displayDanmu(bv, 0, (float) DURATION + 1, true)).isNull();
        assertThat(danmus.displayDanmu(bv, Float.NaN, 5, false)).isNull();
        assertThat(danmus.displayDanmu(bv, 0, Float.NaN, false)).isNull();
        assertThat(danmus.displayDanmu("BVmissing", 0, 5, false)).isNull();
        assertThat(danmus.displayDanmu(null, 0, 5, false)).isNull();
        assertThat(danmus.displayDanmu(unreviewed, 0, 5, false)).isNull();
        assertThat(danmus.displayDanmu(embargoed, 0, 5, true)).isNull();
        assertThat(danmus.displayDanmu(bv, 0, (float) DURATION, false)).isEmpty();
    }

    /** Equal content and equal post time: the filter keeps the danmu with the smaller id. */
    @Test
    void theFilterBreaksPostTimeTiesById() {
        publishedVideoWithAViewer();
        Instant t = fx.now().minus(Duration.ofHours(1));
        long first = fx.danmu(bv, viewer, 7, "same", t);
        long second = fx.danmu(bv, owner, 2, "same", t);

        assertThat(danmus.displayDanmu(bv, 0, 10, true)).containsExactly(first);
        assertThat(danmus.displayDanmu(bv, 0, 10, false)).containsExactly(second, first);
    }

    // likeDanmu ----------------------------------------------------------------------------------

    @Test
    void likeDanmuTogglesForUsersWhoWatchedTheVideo() {
        publishedVideoWithAViewer();
        long id = fx.danmu(bv, owner, 1, "like me", fx.now());

        assertThat(danmus.likeDanmu(fx.auth(viewer), id)).isTrue();
        assertThat(count("select count(*) from danmulikes where danmu_id = ? and mid = ?", id, viewer))
                .isEqualTo(1);
        assertThat(danmus.likeDanmu(fx.auth(viewer), id)).isFalse();
        assertThat(count("select count(*) from danmulikes")).isZero();
        assertThat(danmus.likeDanmu(fx.auth(viewer), id)).isTrue();
    }

    /**
     * As its Javadoc says, likeDanmu checks only that the danmu exists and that the user watched
     * its video; it does not apply the visibility rule of the video interactions.
     */
    @Test
    void likeDanmuIgnoresWhetherTheVideoIsPublished() {
        long owner = fx.user("owner");
        long viewer = fx.user("viewer");
        String hidden = fx.unreviewedVideo(owner, "hidden");
        fx.watch(hidden, viewer, 1);
        long id = fx.danmu(hidden, owner, 1, "before review", fx.now());

        assertThat(danmus.likeDanmu(fx.auth(viewer), id)).isTrue();
    }

    @Test
    void likeDanmuRejectsNonViewersMissingDanmusAndInvalidAuth() {
        publishedVideoWithAViewer();
        long stranger = fx.user("stranger");
        long id = fx.danmu(bv, viewer, 1, "hi", fx.now());

        assertThat(danmus.likeDanmu(fx.auth(stranger), id)).isFalse();
        assertThat(danmus.likeDanmu(fx.auth(viewer), id + 1000)).isFalse();
        assertThat(danmus.likeDanmu(new AuthInfo(viewer, "wrong", null, null), id))
                .isFalse();
        assertThat(danmus.likeDanmu(null, id)).isFalse();
        assertThat(count("select count(*) from danmulikes")).isZero();
    }
}
