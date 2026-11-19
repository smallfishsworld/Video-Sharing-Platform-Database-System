package io.sustc.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.sustc.dto.AuthInfo;
import io.sustc.service.RecommenderService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RecommenderServiceTest extends IntegrationTest {

    @Autowired
    private RecommenderService recommender;

    private List<Long> users(int n) {
        List<Long> mids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            mids.add(fx.user("u" + i));
        }
        return mids;
    }

    // recommendNextVideo -------------------------------------------------------------------------

    @Test
    void similarVideosAreRankedByCommonViewersThenByBv() {
        long owner = fx.user("owner");
        List<Long> u = users(4);
        List<String> v = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            v.add(fx.video(owner, "v" + i));
        }
        String current = v.get(0);
        u.forEach(mid -> fx.watch(current, mid, 1));
        // v1 and v2 tie at 2, v3..v5 tie at 1; rows are inserted in descending bv order so that
        // only the explicit tie-break by bv can produce the expected order.
        fx.watch(v.get(6), u.get(0), 1);
        fx.watch(v.get(6), u.get(1), 1);
        fx.watch(v.get(6), u.get(2), 1);
        fx.watch(v.get(5), u.get(3), 1);
        fx.watch(v.get(4), u.get(2), 1);
        fx.watch(v.get(3), u.get(1), 1);
        fx.watch(v.get(2), u.get(1), 1);
        fx.watch(v.get(2), u.get(0), 1);
        fx.watch(v.get(1), u.get(3), 1);
        fx.watch(v.get(1), u.get(2), 1);
        // v7 is watched by nobody who watched the current video
        fx.watch(v.get(7), owner, 1);

        assertThat(recommender.recommendNextVideo(current))
                .containsExactly(v.get(6), v.get(1), v.get(2), v.get(3), v.get(4));
        assertThat(recommender.recommendNextVideo(v.get(7))).isEmpty();
        DerivedTables.assertConsistent(jdbc);
    }

    @Test
    void recommendNextVideoReturnsNullForMissingVideos() {
        assertThat(recommender.recommendNextVideo("BVmissing")).isNull();
        assertThat(recommender.recommendNextVideo(null)).isNull();
    }

    // generalRecommendations ---------------------------------------------------------------------

    @Test
    void generalRecommendationsRankByTheClampedPopularityScore() {
        long owner = fx.user("owner");
        List<Long> u = users(4);
        String a = fx.video(owner, "a");
        String b = fx.video(owner, "b");
        String c = fx.video(owner, "c");
        String d = fx.video(owner, "d");
        String e = fx.video(owner, "e");
        String f = fx.video(owner, "f");
        // a: finish 0.5 + like 0.5 = 1.0
        fx.watch(a, u.get(0), 100);
        fx.watch(a, u.get(1), 0);
        fx.like(a, u.get(0));
        // b: finish 0.5 + like min(4/1, 1) = 1.5 (4.5 without the clamp)
        fx.watch(b, u.get(0), 50);
        u.forEach(mid -> fx.like(b, mid));
        // c: finish 0.1 + danmu 3/1 = 3.1 (the danmu rate is not clamped)
        fx.watch(c, u.get(0), 10);
        for (int i = 0; i < 3; i++) {
            fx.danmu(c, u.get(0), i, "d" + i, fx.now());
        }
        // d: interactions but no viewers, so every rate is 0
        u.forEach(mid -> {
            fx.like(d, mid);
            fx.coin(d, mid);
            fx.favorite(d, mid);
        });
        // e: finish 1 + like 1 + coin 0.5 + fav 1 = 3.5
        fx.watch(e, u.get(0), 100);
        fx.watch(e, u.get(1), 100);
        fx.like(e, u.get(0));
        fx.like(e, u.get(1));
        fx.coin(e, u.get(0));
        fx.favorite(e, u.get(0));
        fx.favorite(e, u.get(1));
        // f: nothing at all, ties with d at 0 and goes after it by bv

        assertThat(recommender.generalRecommendations(10, 1)).containsExactly(e, c, b, a, d, f);
        assertThat(recommender.generalRecommendations(2, 2)).containsExactly(b, a);
        assertThat(recommender.generalRecommendations(4, 2)).containsExactly(d, f);
        assertThat(recommender.generalRecommendations(2, 4)).isEmpty();
    }

    @Test
    void generalRecommendationsRejectInvalidPages() {
        assertThat(recommender.generalRecommendations(0, 1)).isNull();
        assertThat(recommender.generalRecommendations(1, 0)).isNull();
        assertThat(recommender.generalRecommendations(-1, -1)).isNull();
        assertThat(recommender.generalRecommendations(10, 1)).isEmpty();
    }

    // recommendVideosForUser ---------------------------------------------------------------------

    @Test
    void videosForAUserComeFromTheirFriendsOrderedByFriendsLevelAndPublicTime() {
        long me = fx.user("me");
        long f1 = fx.user("f1");
        long f2 = fx.user("f2");
        long f3 = fx.user("f3");
        long followedOnly = fx.user("followed only");
        long followerOnly = fx.user("follower only");
        fx.befriend(me, f1);
        fx.befriend(me, f2);
        fx.befriend(f3, me);
        fx.follow(me, followedOnly);
        fx.follow(followerOnly, me);
        long high = fx.user("high", 5, 0);
        long low = fx.user("low", 1, 0);
        Instant t = fx.now().minus(Duration.ofDays(10));
        // created in an order that differs from the expected one
        String lowNoPublicTime = fx.video(low, "low, no public time", null, 100, true, null);
        String lowRecent = fx.video(low, "low, recent", null, 100, true, t.plus(Duration.ofDays(5)));
        String highOld = fx.video(high, "high, old", null, 100, true, t);
        String highNew = fx.video(high, "high, new", null, 100, true, t.plus(Duration.ofDays(1)));
        String twoFriends = fx.video(low, "two friends", null, 100, true, t);
        String alreadyWatched = fx.video(high, "already watched");
        String notFromFriends = fx.video(high, "not from friends");
        fx.watch(twoFriends, f1, 1);
        fx.watch(twoFriends, f2, 1);
        fx.watch(highOld, f1, 1);
        fx.watch(highNew, f2, 1);
        fx.watch(lowRecent, f3, 1);
        fx.watch(lowNoPublicTime, f3, 1);
        fx.watch(alreadyWatched, f1, 1);
        fx.watch(alreadyWatched, f2, 1);
        fx.watch(alreadyWatched, me, 1);
        fx.watch(notFromFriends, followedOnly, 1);
        fx.watch(notFromFriends, followerOnly, 1);

        assertThat(recommender.recommendVideosForUser(fx.auth(me), 10, 1))
                .containsExactly(twoFriends, highNew, highOld, lowRecent, lowNoPublicTime);
        assertThat(recommender.recommendVideosForUser(fx.auth(me), 2, 2)).containsExactly(highOld, lowRecent);
        // The interest is not empty, so an empty page stays empty.
        assertThat(recommender.recommendVideosForUser(fx.auth(me), 10, 2)).isEmpty();
    }

    @Test
    void usersWithoutInterestsGetTheGeneralRecommendations() {
        long lonely = fx.user("lonely");
        long me = fx.user("me");
        long friend = fx.user("friend");
        fx.befriend(me, friend);
        String popular = fx.video(friend, "popular");
        String other = fx.video(friend, "other");
        fx.watch(popular, me, 100);
        fx.watch(popular, friend, 100);
        fx.watch(other, lonely, 1);

        List<String> general = recommender.generalRecommendations(10, 1);
        assertThat(general).containsExactly(popular, other);
        assertThat(recommender.recommendVideosForUser(fx.auth(lonely), 10, 1)).isEqualTo(general);
        // every video the friend watched has been watched by "me" already
        assertThat(recommender.recommendVideosForUser(fx.auth(me), 10, 1)).isEqualTo(general);
        assertThat(recommender.recommendVideosForUser(fx.auth(me), 1, 2)).containsExactly(other);
        assertThat(recommender.recommendVideosForUser(fx.auth(me), 1, 3)).isEmpty();
    }

    @Test
    void recommendVideosForUserRejectsInvalidArguments() {
        long me = fx.user("me");
        assertThat(recommender.recommendVideosForUser(new AuthInfo(me, "wrong", null, null), 10, 1))
                .isNull();
        assertThat(recommender.recommendVideosForUser(null, 10, 1)).isNull();
        assertThat(recommender.recommendVideosForUser(fx.auth(me), 0, 1)).isNull();
        assertThat(recommender.recommendVideosForUser(fx.auth(me), 10, 0)).isNull();
    }

    // recommendFriends ---------------------------------------------------------------------------

    @Test
    void friendsAreRankedByCommonFollowingsThenLevelThenMid() {
        long me = fx.user("me");
        long a = fx.user("a");
        long b = fx.user("b");
        long c = fx.user("c");
        long lowLevelTwo = fx.user("low level, two in common", 1, 0);
        long w = fx.user("one in common", 6, 0);
        long x = fx.user("three in common");
        long y = fx.user("y", 3, 0);
        long z = fx.user("z", 3, 0);
        long unrelated = fx.user("unrelated");
        for (long followee : new long[] {a, b, c}) {
            fx.follow(me, followee);
            fx.follow(x, followee);
        }
        for (long candidate : new long[] {y, z, lowLevelTwo}) {
            fx.follow(candidate, a);
            fx.follow(candidate, b);
        }
        fx.follow(w, a);
        // a shares a followee with me but is followed by me already
        fx.follow(a, b);
        fx.follow(unrelated, me);

        assertThat(recommender.recommendFriends(fx.auth(me), 10, 1)).containsExactly(x, y, z, lowLevelTwo, w);
        assertThat(recommender.recommendFriends(fx.auth(me), 2, 2)).containsExactly(z, lowLevelTwo);
        assertThat(recommender.recommendFriends(fx.auth(me), 2, 4)).isEmpty();
        assertThat(recommender.recommendFriends(fx.auth(unrelated), 10, 1)).isEmpty();
    }

    @Test
    void recommendFriendsRejectsInvalidArguments() {
        long me = fx.user("me");
        assertThat(recommender.recommendFriends(new AuthInfo(me, "wrong", null, null), 10, 1))
                .isNull();
        assertThat(recommender.recommendFriends(fx.auth(me), 0, 1)).isNull();
        assertThat(recommender.recommendFriends(fx.auth(me), 1, 0)).isNull();
    }
}
