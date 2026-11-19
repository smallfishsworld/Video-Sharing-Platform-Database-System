package io.sustc.service.impl;

import io.sustc.dto.AuthInfo;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inserts test data with plain SQL, so the triggers that maintain the derived tables run exactly
 * as they do for any other writer.
 */
final class Fixtures {

    static final double DEFAULT_DURATION = 100;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<Long, String> passwords = new HashMap<>();
    private int videoCounter;

    Fixtures(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    long user(String name) {
        return user(name, 0, 0);
    }

    long user(String name, int level, int coin) {
        return insertUser(name, level, coin, "USER", null, null);
    }

    long superuser(String name) {
        return insertUser(name, 0, 0, "SUPERUSER", null, null);
    }

    long userWithOidc(String name, String qq, String wechat) {
        return insertUser(name, 0, 0, "USER", qq, wechat);
    }

    private long insertUser(String name, int level, int coin, String identity, String qq, String wechat) {
        String password = "pw-" + name;
        Long mid = jdbc.queryForObject(
                "insert into users (name, sex, level, coin, identity, password, qq, wechat)"
                        + " values (?, '保密', ?, ?, ?, ?, ?, ?) returning mid",
                Long.class,
                name,
                level,
                coin,
                identity,
                password,
                qq,
                wechat);
        passwords.put(mid, password);
        return mid;
    }

    /** Password authentication for a fixture user. */
    AuthInfo auth(long mid) {
        return AuthInfo.builder().mid(mid).password(passwords.get(mid)).build();
    }

    /** A reviewed video that became public an hour ago. */
    String video(long owner, String title) {
        return video(owner, title, null, DEFAULT_DURATION, true, now().minus(Duration.ofHours(1)));
    }

    String unreviewedVideo(long owner, String title) {
        return video(owner, title, null, DEFAULT_DURATION, false, now().minus(Duration.ofHours(1)));
    }

    String video(long owner, String title, String description, double duration, boolean reviewed, Instant publicTime) {
        return video("BVtest%05d".formatted(++videoCounter), owner, title, description, duration, reviewed, publicTime);
    }

    /** A reviewed, public video with the given bv. */
    String videoWithBv(String bv, long owner, String title) {
        return video(bv, owner, title, null, DEFAULT_DURATION, true, now().minus(Duration.ofHours(1)));
    }

    private String video(
            String bv,
            long owner,
            String title,
            String description,
            double duration,
            boolean reviewed,
            Instant publicTime) {
        Timestamp commit = ts(now().minus(Duration.ofDays(1)));
        jdbc.update(
                "insert into videos (bv, title, owner_mid, owner_name, commit_time, review_time, reviewer,"
                        + " public_time, duration, description)"
                        + " select ?, ?, mid, name, ?, ?, null, ?, ?, ? from users where mid = ?",
                bv,
                title,
                commit,
                reviewed ? commit : null,
                publicTime == null ? null : ts(publicTime),
                duration,
                description,
                owner);
        return bv;
    }

    void watch(String bv, long mid, double viewTime) {
        jdbc.update("insert into viewers (bv, mid, view_time) values (?, ?, ?)", bv, mid, viewTime);
    }

    void like(String bv, long mid) {
        jdbc.update("insert into videolikes (bv, mid) values (?, ?)", bv, mid);
    }

    /** Records a donation without charging the donor (as imported data would). */
    void coin(String bv, long mid) {
        jdbc.update("insert into coins (bv, mid) values (?, ?)", bv, mid);
    }

    void favorite(String bv, long mid) {
        jdbc.update("insert into favorites (bv, mid) values (?, ?)", bv, mid);
    }

    void follow(long follower, long followee) {
        jdbc.update("insert into followings (follower_mid, followee_mid) values (?, ?)", follower, followee);
    }

    void befriend(long a, long b) {
        follow(a, b);
        follow(b, a);
    }

    long danmu(String bv, long mid, double time, String content, Instant postTime) {
        Long id = jdbc.queryForObject(
                "insert into danmus (bv, mid, time, content, post_time) values (?, ?, ?, ?, ?) returning id",
                Long.class,
                bv,
                mid,
                time,
                content,
                ts(postTime));
        return id;
    }

    void likeDanmu(long danmuId, long mid) {
        jdbc.update("insert into danmulikes (danmu_id, mid) values (?, ?)", danmuId, mid);
    }

    Instant now() {
        return clock.instant();
    }

    static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
