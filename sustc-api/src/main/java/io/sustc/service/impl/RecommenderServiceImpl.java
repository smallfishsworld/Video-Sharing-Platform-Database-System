package io.sustc.service.impl;

import io.sustc.dto.AuthInfo;
import io.sustc.service.RecommenderService;
import io.sustc.service.impl.auth.Authenticator;
import io.sustc.service.impl.auth.CurrentUser;
import io.sustc.service.impl.support.Page;
import io.sustc.service.impl.support.SqlArrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RecommenderServiceImpl implements RecommenderService {

    static final int SIMILAR_VIDEOS = 5;

    /**
     * One row (the top bvs, possibly none) if the video exists, no row otherwise. Similarity is
     * the number of users who watched both videos (kept in video_coviews); ties are broken by bv.
     */
    private static final String SIMILAR =
            """
            select array(
                select c.other_bv
                from video_coviews c
                where c.bv = v.bv
                order by c.viewers desc, c.other_bv
                limit :limit)
            from videos v
            where v.bv = :bv
            """;

    /**
     * score = like + coin + fav + danmu + finish, each relative to the number of viewers. The
     * three rates are clamped to 1 because users can interact without watching; a video nobody
     * watched scores 0. All inputs are per-video counters from video_stats.
     */
    private static final String GENERAL =
            """
            select v.bv
            from videos v
            join video_stats s on s.bv = v.bv
            order by case when s.viewers = 0 then 0
                          else least(s.likes::double precision / s.viewers, 1)
                             + least(s.coins::double precision / s.viewers, 1)
                             + least(s.favorites::double precision / s.viewers, 1)
                             + s.danmus::double precision / s.viewers
                             + s.view_time_sum / s.viewers / v.duration
                     end desc,
                     v.bv
            limit :limit offset :offset
            """;

    /**
     * Videos watched by friends (users who follow the current user and are followed back), not
     * watched by the current user, ranked by the number of such friends, the owner's level and
     * the public time (newest first, videos without one last), then by bv for stable pages.
     */
    private static final String FOR_USER =
            """
            with friends as (
                select f.followee_mid as mid
                from followings f
                join followings back on back.follower_mid = f.followee_mid and back.followee_mid = f.follower_mid
                where f.follower_mid = :me
            )
            select w.bv
            from friends fr
            join viewers w on w.mid = fr.mid
            join videos v on v.bv = w.bv
            join users owner on owner.mid = v.owner_mid
            where not exists (select 1 from viewers mine where mine.bv = w.bv and mine.mid = :me)
            group by w.bv, owner.level, v.public_time
            order by count(*) desc, owner.level desc, v.public_time desc nulls last, w.bv
            limit :limit offset :offset
            """;

    /** Whether the current user has any interest at all; asked only when a page came back empty. */
    private static final String HAS_INTEREST =
            """
            select exists (
                select 1
                from followings f
                join followings back on back.follower_mid = f.followee_mid and back.followee_mid = f.follower_mid
                join viewers w on w.mid = f.followee_mid
                where f.follower_mid = :me
                  and not exists (select 1 from viewers mine where mine.bv = w.bv and mine.mid = :me))
            """;

    /**
     * Users not followed by the current user (and not the user), ranked by the number of
     * followees they share with the current user, then by level (desc) and mid. The two
     * exclusions are applied after counting, to the distinct candidates, instead of to every
     * (followee, follower) row of the join, which for a user with many followings is 10x larger.
     */
    private static final String FRIENDS =
            """
            select c.mid
            from (select o.follower_mid as mid, count(*) as common
                  from followings mine
                  join followings o on o.followee_mid = mine.followee_mid
                  where mine.follower_mid = :me
                  group by o.follower_mid) c
            join users u on u.mid = c.mid
            where c.mid <> :me
              and not exists (select 1 from followings x where x.follower_mid = :me and x.followee_mid = c.mid)
            order by c.common desc, u.level desc, c.mid
            limit :limit offset :offset
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Authenticator authenticator;

    public RecommenderServiceImpl(NamedParameterJdbcTemplate jdbc, Authenticator authenticator) {
        this.jdbc = jdbc;
        this.authenticator = authenticator;
    }

    @Override
    public List<String> recommendNextVideo(String bv) {
        if (bv == null) {
            return null;
        }
        MapSqlParameterSource params =
                new MapSqlParameterSource().addValue("bv", bv).addValue("limit", SIMILAR_VIDEOS);
        List<List<String>> rows = jdbc.query(SIMILAR, params, (rs, i) -> SqlArrays.toStringList(rs.getArray(1)));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<String> generalRecommendations(int pageSize, int pageNum) {
        return Page.of(pageSize, pageNum).map(this::general).orElse(null);
    }

    private List<String> general(Page page) {
        return jdbc.queryForList(GENERAL, pageParams(page), String.class);
    }

    @Override
    public List<String> recommendVideosForUser(AuthInfo auth, int pageSize, int pageNum) {
        Optional<CurrentUser> me = authenticator.authenticate(auth);
        Optional<Page> page = Page.of(pageSize, pageNum);
        if (me.isEmpty() || page.isEmpty()) {
            return null;
        }
        MapSqlParameterSource params =
                pageParams(page.get()).addValue("me", me.get().mid());
        List<String> result = jdbc.queryForList(FOR_USER, params, String.class);
        if (result.isEmpty() && !Boolean.TRUE.equals(jdbc.queryForObject(HAS_INTEREST, params, Boolean.class))) {
            return general(page.get());
        }
        return result;
    }

    @Override
    public List<Long> recommendFriends(AuthInfo auth, int pageSize, int pageNum) {
        Optional<CurrentUser> me = authenticator.authenticate(auth);
        Optional<Page> page = Page.of(pageSize, pageNum);
        if (me.isEmpty() || page.isEmpty()) {
            return null;
        }
        return jdbc.queryForList(
                FRIENDS, pageParams(page.get()).addValue("me", me.get().mid()), Long.class);
    }

    private static MapSqlParameterSource pageParams(Page page) {
        return new MapSqlParameterSource().addValue("limit", page.size()).addValue("offset", page.offset());
    }
}
