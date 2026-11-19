package io.sustc.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.sustc.dto.AuthInfo;
import io.sustc.dto.RegisterUserReq;
import io.sustc.dto.RegisterUserReq.Gender;
import io.sustc.dto.UserInfoResp;
import io.sustc.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

class UserServiceTest extends IntegrationTest {

    @Autowired
    private UserService users;

    private static RegisterUserReq.RegisterUserReqBuilder validRequest() {
        return RegisterUserReq.builder()
                .name("neo")
                .password("secret")
                .sex(Gender.MALE)
                .birthday("2月29日")
                .sign("hello");
    }

    @Test
    void registerStoresTheProfileAndReturnsTheNewMid() {
        long mid = users.register(validRequest().qq("10001").wechat("wx-neo").build());

        assertThat(mid).isPositive();
        assertThat(jdbc.queryForMap("select name, sex, birthday, level, coin, identity, qq, wechat from users"))
                .containsEntry("name", "neo")
                .containsEntry("sex", "男")
                .containsEntry("birthday", "2月29日")
                .containsEntry("level", 0)
                .containsEntry("coin", 0)
                .containsEntry("identity", "USER")
                .containsEntry("qq", "10001")
                .containsEntry("wechat", "wx-neo");
    }

    /** An empty sign is a value, not an absence: it is stored as '' (only empty OIDC ids mean none). */
    @Test
    void registerStoresAnEmptySignAsAnEmptyString() {
        long mid = users.register(validRequest().sign("").build());

        assertThat(jdbc.queryForObject("select sign from users where mid = ?", String.class, mid))
                .isEmpty();
    }

    @Test
    void registerRejectsMissingRequiredFields() {
        assertThat(users.register(null)).isEqualTo(-1);
        assertThat(users.register(validRequest().password(null).build())).isEqualTo(-1);
        assertThat(users.register(validRequest().password("").build())).isEqualTo(-1);
        assertThat(users.register(validRequest().name(null).build())).isEqualTo(-1);
        assertThat(users.register(validRequest().name("").build())).isEqualTo(-1);
        assertThat(users.register(validRequest().sex(null).build())).isEqualTo(-1);
        assertThat(count("select count(*) from users")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1月1日", "2月29日", "4月30日", "12月31日"})
    @NullAndEmptySource
    void registerAcceptsRealBirthdays(String birthday) {
        assertThat(users.register(validRequest().birthday(birthday).build())).isPositive();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2月30日", "4月31日", "13月1日", "0月1日", "1月0日", "1月1", "Jan 1", "01-01", "1月1日 "})
    void registerRejectsInvalidBirthdays(String birthday) {
        assertThat(users.register(validRequest().birthday(birthday).build())).isEqualTo(-1);
    }

    @Test
    void registerRejectsADuplicateQqOrWechatButNotTwoEmptyOnes() {
        fx.userWithOidc("taken", "10001", "wx-taken");

        assertThat(users.register(validRequest().qq("10001").build())).isEqualTo(-1);
        assertThat(users.register(validRequest().wechat("wx-taken").build())).isEqualTo(-1);
        assertThat(users.register(validRequest().qq("").wechat("").build())).isPositive();
        assertThat(users.register(validRequest().qq("").wechat(null).build())).isPositive();
        assertThat(count("select count(*) from users where qq is null and wechat is null"))
                .isEqualTo(2);
    }

    @Test
    void usersCanDeleteOnlyTheirOwnAccount() {
        long alice = fx.user("alice");
        long bob = fx.user("bob");

        assertThat(users.deleteAccount(fx.auth(alice), bob)).isFalse();
        assertThat(users.deleteAccount(fx.auth(alice), alice)).isTrue();
        assertThat(count("select count(*) from users")).isEqualTo(1);
    }

    @Test
    void superusersCanDeleteRegularUsersAndThemselvesButNotOtherSuperusers() {
        long admin = fx.superuser("admin");
        long other = fx.superuser("other-admin");
        long user = fx.user("user");

        assertThat(users.deleteAccount(fx.auth(admin), other)).isFalse();
        assertThat(users.deleteAccount(fx.auth(admin), user)).isTrue();
        assertThat(users.deleteAccount(fx.auth(admin), admin)).isTrue();
        assertThat(jdbc.queryForList("select mid from users", Long.class)).containsExactly(other);
    }

    @Test
    void deleteAccountFailsForMissingUsersAndInvalidAuth() {
        long alice = fx.user("alice");
        long admin = fx.superuser("admin");

        assertThat(users.deleteAccount(fx.auth(admin), 424242)).isFalse();
        assertThat(users.deleteAccount(null, alice)).isFalse();
        assertThat(users.deleteAccount(
                        AuthInfo.builder().mid(alice).password("wrong").build(), alice))
                .isFalse();
        assertThat(count("select count(*) from users")).isEqualTo(2);
    }

    @Test
    void deleteAccountRemovesEverythingTheUserCreatedOrTouched() {
        long doomed = fx.user("doomed");
        long friend = fx.user("friend");
        long fan = fx.user("fan");
        String theirVideo = fx.video(friend, "friend's video");
        String ownVideo = fx.video(doomed, "doomed's video");
        fx.follow(doomed, friend);
        fx.follow(fan, doomed);
        fx.watch(theirVideo, doomed, 10);
        fx.like(theirVideo, doomed);
        fx.coin(theirVideo, doomed);
        fx.favorite(theirVideo, doomed);
        long ownDanmu = fx.danmu(theirVideo, doomed, 1, "hi", fx.now());
        long fanDanmu = fx.danmu(theirVideo, fan, 2, "yo", fx.now());
        fx.likeDanmu(fanDanmu, doomed);
        // Interactions of other users with the doomed user's own video go as well.
        fx.watch(ownVideo, fan, 20);
        fx.watch(theirVideo, fan, 30);
        fx.like(ownVideo, fan);
        fx.coin(ownVideo, fan);
        fx.favorite(ownVideo, fan);
        long danmuOnOwnVideo = fx.danmu(ownVideo, fan, 3, "nice", fx.now());
        fx.likeDanmu(danmuOnOwnVideo, friend);

        assertThat(users.deleteAccount(fx.auth(doomed), doomed)).isTrue();

        assertThat(count("select count(*) from followings where ? in (follower_mid, followee_mid)", doomed))
                .isZero();
        assertThat(count("select count(*) from videos where bv = ?", ownVideo)).isZero();
        for (String table : new String[] {"viewers", "videolikes", "coins", "favorites", "danmus"}) {
            assertThat(count("select count(*) from " + table + " where mid = ? or bv = ?", doomed, ownVideo))
                    .as(table)
                    .isZero();
        }
        assertThat(jdbc.queryForList("select id from danmus", Long.class)).containsExactly(fanDanmu);
        assertThat(count("select count(*) from danmulikes")).isZero();
        assertThat(count("select count(*) from danmus where id = ?", ownDanmu)).isZero();
        // What other users did elsewhere is kept.
        assertThat(count("select count(*) from viewers where mid = ? and bv = ?", fan, theirVideo))
                .isEqualTo(1);
        DerivedTables.assertConsistent(jdbc);
    }

    @Test
    void followTogglesAndReturnsTheStateAfterTheCall() {
        long alice = fx.user("alice");
        long bob = fx.user("bob");

        assertThat(users.follow(fx.auth(alice), bob)).isTrue();
        assertThat(count("select count(*) from followings where follower_mid = ? and followee_mid = ?", alice, bob))
                .isEqualTo(1);
        assertThat(users.follow(fx.auth(alice), bob)).isFalse();
        assertThat(count("select count(*) from followings")).isZero();
        assertThat(users.follow(fx.auth(alice), bob)).isTrue();
    }

    @Test
    void followRejectsSelfMissingUsersAndInvalidAuth() {
        long alice = fx.user("alice");
        long bob = fx.user("bob");

        assertThat(users.follow(fx.auth(alice), alice)).isFalse();
        assertThat(users.follow(fx.auth(alice), 424242)).isFalse();
        assertThat(users.follow(AuthInfo.builder().mid(alice).password("wrong").build(), bob))
                .isFalse();
        assertThat(users.follow(null, bob)).isFalse();
        assertThat(count("select count(*) from followings")).isZero();
    }

    @Test
    void getUserInfoListsEveryRelation() {
        long alice = fx.user("alice", 2, 7);
        long bob = fx.user("bob");
        long carol = fx.user("carol");
        String own = fx.video(alice, "alice's video");
        String other = fx.video(bob, "bob's video");
        fx.follow(alice, bob);
        fx.follow(carol, alice);
        fx.watch(other, alice, 5);
        fx.like(other, alice);
        fx.favorite(own, alice);

        UserInfoResp info = users.getUserInfo(alice);

        assertThat(info.getMid()).isEqualTo(alice);
        assertThat(info.getCoin()).isEqualTo(7);
        assertThat(info.getFollowing()).containsExactly(bob);
        assertThat(info.getFollower()).containsExactly(carol);
        assertThat(info.getWatched()).containsExactly(other);
        assertThat(info.getLiked()).containsExactly(other);
        assertThat(info.getCollected()).containsExactly(own);
        assertThat(info.getPosted()).containsExactly(own);
        assertThat(users.getUserInfo(carol).getPosted()).isEmpty();
        assertThat(users.getUserInfo(424242)).isNull();
    }

    @Test
    void authenticationFollowsTheRulesOfTheServiceInterface() {
        long alice = fx.userWithOidc("alice", "qq-alice", "wx-alice");
        long bob = fx.userWithOidc("bob", "qq-bob", "wx-bob");
        long target = fx.user("target");
        String alicePassword = "pw-alice";

        // mid + password
        assertThat(users.follow(fx.auth(alice), target)).isTrue();
        // a wrong password falls back to qq, then wechat
        assertThat(users.follow(new AuthInfo(alice, "wrong", "qq-alice", null), target))
                .isFalse(); // unfollowed: the qq identified alice
        assertThat(users.follow(new AuthInfo(0, null, null, "wx-alice"), target))
                .isTrue();
        // qq and wechat together must belong to the same user, even if the password is right
        assertThat(users.follow(new AuthInfo(alice, alicePassword, "qq-alice", "wx-bob"), target))
                .isFalse();
        assertThat(count("select count(*) from followings where follower_mid = ?", alice))
                .isEqualTo(1);
        assertThat(users.follow(new AuthInfo(0, null, "qq-bob", "wx-bob"), target))
                .isTrue();
        // empty strings count as absent
        assertThat(users.follow(new AuthInfo(bob, "", "", ""), target)).isFalse();
        assertThat(users.follow(new AuthInfo(bob, "", "qq-unknown", ""), target))
                .isFalse();
        assertThat(count("select count(*) from followings where follower_mid = ?", bob))
                .isEqualTo(1);
    }

    @Test
    void registeredUsersGetFreshIdsAfterExistingOnes() {
        long existing = fx.user("existing");
        long registered = users.register(validRequest().build());
        assertThat(registered).isGreaterThan(existing);
        assertThat(users.getUserInfo(registered).getCoin()).isZero();
        // and the account works right away
        assertThat(users.follow(new AuthInfo(registered, "secret", null, null), existing))
                .isTrue();
    }
}
