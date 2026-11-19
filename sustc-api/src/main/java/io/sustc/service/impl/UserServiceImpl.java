package io.sustc.service.impl;

import io.sustc.dto.AuthInfo;
import io.sustc.dto.RegisterUserReq;
import io.sustc.dto.UserInfoResp;
import io.sustc.dto.UserRecord;
import io.sustc.service.UserService;
import io.sustc.service.impl.auth.Authenticator;
import io.sustc.service.impl.auth.CurrentUser;
import io.sustc.service.impl.support.SqlArrays;
import io.sustc.service.impl.support.Strings;
import io.sustc.service.impl.support.Writes;
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {

    static final long INVALID_MID = -1;

    private static final Pattern BIRTHDAY = Pattern.compile("(\\d{1,2})月(\\d{1,2})日");

    /** Days per month; February allows the 29th because a birthday has no year. */
    private static final int[] DAYS_IN_MONTH = {31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

    /**
     * New users get the column defaults: level 0, no coins, identity USER. Conflicts on the unique
     * qq/wechat columns make the insert return no row, which closes the race between "check for
     * duplicates" and "insert".
     */
    private static final String INSERT_USER =
            """
            insert into users (name, sex, birthday, sign, password, qq, wechat)
            values (:name, :sex, :birthday, :sign, :password, :qq, :wechat)
            on conflict do nothing
            returning mid
            """;

    /**
     * A regular user may delete only their own account; a superuser may also delete regular
     * users. The foreign keys cascade, so this single statement also removes the user's videos
     * (with every interaction on them), danmus, follow relations and interaction records. It runs
     * after {@link Writes#LOCK_DELETIONS_EXCLUSIVE}, so no other deletion runs at the same time.
     */
    private static final String DELETE_USER = Writes.LOCK_DELETIONS_EXCLUSIVE
            + """
            delete from users
            where mid = :target
              and (mid = :me or (:superuser and identity = :regularIdentity))
            """;

    /**
     * Toggles the relation and reports whether the user follows the followee afterwards. Runs
     * after {@link Writes#LOCK_ACTING_USER}, so concurrent toggles by one user are applied one
     * after the other.
     */
    private static final String TOGGLE_FOLLOW = Writes.LOCK_ACTING_USER
            + """
            with followee as (
                select mid from users where mid = :followee and mid <> :me
            ),
            removed as (
                delete from followings f
                using followee
                where f.follower_mid = :me and f.followee_mid = followee.mid
                returning f.followee_mid
            ),
            added as (
                insert into followings (follower_mid, followee_mid)
                select :me, mid from followee
                where not exists (select 1 from removed)
                on conflict do nothing
                returning followee_mid
            )
            select exists (select 1 from added)
            """;

    private static final String USER_INFO =
            """
            select u.mid,
                   u.coin,
                   array(select followee_mid from followings where follower_mid = u.mid) as following,
                   array(select follower_mid from followings where followee_mid = u.mid) as follower,
                   array(select bv from viewers where mid = u.mid) as watched,
                   array(select bv from videolikes where mid = u.mid) as liked,
                   array(select bv from favorites where mid = u.mid) as collected,
                   array(select bv from videos where owner_mid = u.mid) as posted
            from users u
            where u.mid = :mid
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final DataSource dataSource;
    private final Authenticator authenticator;

    public UserServiceImpl(NamedParameterJdbcTemplate jdbc, Authenticator authenticator) {
        this.jdbc = jdbc;
        this.dataSource = Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource());
        this.authenticator = authenticator;
    }

    @Override
    public long register(RegisterUserReq req) {
        if (req == null
                || Strings.isNullOrEmpty(req.getPassword())
                || Strings.isNullOrEmpty(req.getName())
                || req.getSex() == null
                || !isValidBirthday(req.getBirthday())) {
            return INVALID_MID;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("name", req.getName())
                .addValue("sex", storedSex(req.getSex()))
                .addValue("birthday", Strings.emptyToNull(req.getBirthday()), Types.VARCHAR)
                .addValue("sign", req.getSign(), Types.VARCHAR)
                .addValue("password", req.getPassword())
                .addValue("qq", Strings.emptyToNull(req.getQq()), Types.VARCHAR)
                .addValue("wechat", Strings.emptyToNull(req.getWechat()), Types.VARCHAR);
        return Writes.run(
                dataSource,
                () -> jdbc.queryForList(INSERT_USER, params, Long.class).stream()
                        .findFirst()
                        .orElse(INVALID_MID),
                INVALID_MID);
    }

    @Override
    public boolean deleteAccount(AuthInfo auth, long mid) {
        Optional<CurrentUser> me = authenticator.authenticate(auth);
        if (me.isEmpty()) {
            return false;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("target", mid)
                .addValue("me", me.get().mid())
                .addValue("superuser", me.get().superuser())
                .addValue("regularIdentity", UserRecord.Identity.USER.name());
        return Writes.run(
                dataSource, () -> jdbc.execute(DELETE_USER, params, Writes.UPDATE_COUNT_AFTER_LOCK) == 1, false);
    }

    @Override
    public boolean follow(AuthInfo auth, long followeeMid) {
        Optional<CurrentUser> me = authenticator.authenticate(auth);
        if (me.isEmpty()) {
            return false;
        }
        MapSqlParameterSource params =
                new MapSqlParameterSource().addValue("me", me.get().mid()).addValue("followee", followeeMid);
        return Writes.run(dataSource, () -> jdbc.execute(TOGGLE_FOLLOW, params, Writes.RESULT_AFTER_LOCK), false);
    }

    @Override
    public UserInfoResp getUserInfo(long mid) {
        return jdbc
                .query(USER_INFO, new MapSqlParameterSource("mid", mid), (rs, rowNum) -> UserInfoResp.builder()
                        .mid(rs.getLong("mid"))
                        .coin(rs.getInt("coin"))
                        .following(SqlArrays.toLongArray(rs.getArray("following")))
                        .follower(SqlArrays.toLongArray(rs.getArray("follower")))
                        .watched(SqlArrays.toStringArray(rs.getArray("watched")))
                        .liked(SqlArrays.toStringArray(rs.getArray("liked")))
                        .collected(SqlArrays.toStringArray(rs.getArray("collected")))
                        .posted(SqlArrays.toStringArray(rs.getArray("posted")))
                        .build())
                .stream()
                .findFirst()
                .orElse(null);
    }

    /** A birthday is optional; when present it must be a real calendar day such as {@code 2月29日}. */
    static boolean isValidBirthday(String birthday) {
        if (Strings.isNullOrEmpty(birthday)) {
            return true;
        }
        Matcher m = BIRTHDAY.matcher(birthday);
        if (!m.matches()) {
            return false;
        }
        int month = Integer.parseInt(m.group(1));
        int day = Integer.parseInt(m.group(2));
        return month >= 1 && month <= 12 && day >= 1 && day <= DAYS_IN_MONTH[month - 1];
    }

    /** Stores the gender with the same labels that the imported data uses. */
    static String storedSex(RegisterUserReq.Gender gender) {
        return switch (gender) {
            case MALE -> "男";
            case FEMALE -> "女";
            case UNKNOWN -> "保密";
        };
    }
}
