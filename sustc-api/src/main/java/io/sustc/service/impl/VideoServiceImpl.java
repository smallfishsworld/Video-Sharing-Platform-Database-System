package io.sustc.service.impl;

import io.sustc.dto.AuthInfo;
import io.sustc.dto.PostVideoReq;
import io.sustc.service.VideoService;
import io.sustc.service.impl.auth.Authenticator;
import io.sustc.service.impl.auth.CurrentUser;
import io.sustc.service.impl.support.Page;
import io.sustc.service.impl.support.SqlArrays;
import io.sustc.service.impl.support.Strings;
import io.sustc.service.impl.support.VideoVisibility;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class VideoServiceImpl implements VideoService {

    static final float MIN_DURATION_SECONDS = 10;
    static final int HOTSPOT_CHUNK_SECONDS = 10;
    private static final int MAX_BV_ATTEMPTS = 5;
    private static final String BV_PREFIX = "BV1";
    private static final int BV_RANDOM_LENGTH = 9;
    private static final String BV_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /**
     * The owner name is copied from the users table. A duplicate (owner, title) makes the insert
     * return no row; a bv collision raises a unique violation and the caller retries.
     */
    private static final String INSERT_VIDEO =
            """
            insert into videos (bv, title, owner_mid, owner_name, commit_time, public_time, duration, description)
            select :bv, :title, u.mid, u.name, :now, :publicTime, :duration, :description
            from users u
            where u.mid = :me
            on conflict (owner_mid, title) do nothing
            returning bv
            """;

    /** Foreign keys cascade to every interaction with the video, so one statement suffices. */
    private static final String DELETE_VIDEO = "delete from videos where bv = :bv and (:superuser or owner_mid = :me)";

    /**
     * Applies an owner's edit if the duration is unchanged and something actually changed. Any
     * edit sends the video back for review; the result says whether it had been reviewed.
     */
    private static final String UPDATE_VIDEO =
            """
            update videos v
            set title = :title, description = :description, public_time = :publicTime,
                review_time = null, reviewer = null
            from videos old
            where old.bv = v.bv
              and v.bv = :bv
              and v.owner_mid = :me
              and v.duration = :duration
              and (v.title, v.description, v.public_time) is distinct from (:title, :description, :publicTime)
            returning old.review_time is not null
            """;

    /**
     * Relevance is the total number of non-overlapping, case-insensitive occurrences of every
     * keyword in the title, description and owner name. Ties are broken by view count, then by
     * bv so that pages are stable.
     */
    private static final String SEARCH =
            """
            select v.bv
            from videos v
            cross join lateral (
                select sum(count_occurrences(lower(v.title), k.word)
                         + count_occurrences(lower(coalesce(v.description, '')), k.word)
                         + count_occurrences(lower(v.owner_name), k.word)) as relevance
                from (select lower(w) as word from unnest(:keywords) as w) k
            ) r
            where r.relevance > 0
              and %s
            order by r.relevance desc, (select count(*) from viewers w where w.bv = v.bv) desc, v.bv
            limit :limit offset :offset
            """
                    .formatted(VideoVisibility.VISIBLE_TO_ME);

    private static final String AVERAGE_VIEW_RATE =
            """
            select avg(w.view_time) / v.duration
            from videos v
            join viewers w on w.bv = v.bv
            where v.bv = :bv
            group by v.bv
            """;

    private static final String HOTSPOTS =
            """
            select chunk
            from (select floor(time / :chunk)::integer as chunk, rank() over (order by count(*) desc) as rnk
                  from danmus
                  where bv = :bv
                  group by chunk) ranked
            where rnk = 1
            """;

    /** The conditions in the UPDATE make concurrent reviews of the same video safe. */
    private static final String REVIEW_VIDEO =
            """
            update videos set review_time = :now, reviewer = :me
            where bv = :bv and review_time is null and owner_mid <> :me
            """;

    /**
     * Charges one coin and records the donation in one statement, so it is atomic: the coin is
     * only taken if the donation row is inserted, and {@code coin > 0} together with the row lock
     * taken by the UPDATE serializes concurrent donations by one user. A concurrent donation to
     * the same video fails on the coins primary key and the whole statement rolls back.
     */
    private static final String DONATE_COIN =
            """
            with target as (
                select v.bv
                from videos v
                where v.bv = :bv
                  and v.owner_mid <> :me
                  and %s
                  and not exists (select 1 from coins c where c.bv = v.bv and c.mid = :me)
            ),
            charged as (
                update users set coin = coin - 1
                where mid = :me and coin > 0 and exists (select 1 from target)
                returning mid
            )
            insert into coins (bv, mid)
            select target.bv, charged.mid from target, charged
            """
                    .formatted(VideoVisibility.VISIBLE_TO_ME);

    /** Like/collect toggle: removes the row if present, inserts it otherwise; returns the new state. */
    private static final String TOGGLE =
            """
            with target as (
                select v.bv from videos v
                where v.bv = :bv and v.owner_mid <> :me and %s
            ),
            removed as (
                delete from %s t
                using target
                where t.bv = target.bv and t.mid = :me
                returning t.bv
            ),
            added as (
                insert into %2$s (bv, mid)
                select bv, :me from target
                where not exists (select 1 from removed)
                on conflict do nothing
                returning bv
            )
            select exists (select 1 from added)
            """;

    private static final String TOGGLE_LIKE = TOGGLE.formatted(VideoVisibility.VISIBLE_TO_ME, "videolikes");
    private static final String TOGGLE_FAVORITE = TOGGLE.formatted(VideoVisibility.VISIBLE_TO_ME, "favorites");

    private final NamedParameterJdbcTemplate jdbc;
    private final Authenticator authenticator;
    private final Clock clock;

    public VideoServiceImpl(NamedParameterJdbcTemplate jdbc, Authenticator authenticator, Clock clock) {
        this.jdbc = jdbc;
        this.authenticator = authenticator;
        this.clock = clock;
    }

    @Override
    public String postVideo(AuthInfo auth, PostVideoReq req) {
        Timestamp now = now();
        Optional<CurrentUser> me = authenticator.authenticate(auth);
        if (me.isEmpty() || !isValid(req, now)) {
            return null;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("me", me.get().mid())
                .addValue("title", req.getTitle())
                .addValue("now", now)
                .addValue("publicTime", req.getPublicTime())
                .addValue("duration", (double) req.getDuration())
                .addValue("description", req.getDescription(), Types.VARCHAR);
        for (int attempt = 1; attempt <= MAX_BV_ATTEMPTS; attempt++) {
            params.addValue("bv", newBv());
            try {
                return jdbc.queryForList(INSERT_VIDEO, params, String.class).stream()
                        .findFirst()
                        .orElse(null);
            } catch (DuplicateKeyException e) {
                log.debug("Generated bv collided with an existing video (attempt {})", attempt);
            }
        }
        throw new IllegalStateException("Could not generate an unused bv in " + MAX_BV_ATTEMPTS + " attempts");
    }

    @Override
    public boolean deleteVideo(AuthInfo auth, String bv) {
        Optional<CurrentUser> me = authenticator.authenticate(auth);
        if (me.isEmpty() || bv == null) {
            return false;
        }
        return jdbc.update(DELETE_VIDEO, VideoVisibility.bind(me.get(), now()).addValue("bv", bv)) == 1;
    }

    @Override
    public boolean updateVideoInfo(AuthInfo auth, String bv, PostVideoReq req) {
        Timestamp now = now();
        Optional<CurrentUser> me = authenticator.authenticate(auth);
        if (me.isEmpty() || bv == null || !isValid(req, now)) {
            return false;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("bv", bv)
                .addValue("me", me.get().mid())
                .addValue("title", req.getTitle())
                .addValue("description", req.getDescription(), Types.VARCHAR)
                .addValue("publicTime", req.getPublicTime())
                .addValue("duration", (double) req.getDuration());
        try {
            List<Boolean> wasReviewed = jdbc.queryForList(UPDATE_VIDEO, params, Boolean.class);
            return !wasReviewed.isEmpty() && wasReviewed.get(0);
        } catch (DuplicateKeyException e) {
            // The owner already has another video with the new title.
            return false;
        }
    }

    @Override
    public List<String> searchVideo(AuthInfo auth, String keywords, int pageSize, int pageNum) {
        Optional<CurrentUser> me = authenticator.authenticate(auth);
        Optional<Page> page = Page.of(pageSize, pageNum);
        if (me.isEmpty() || Strings.isNullOrEmpty(keywords) || page.isEmpty()) {
            return null;
        }
        String[] words = splitKeywords(keywords);
        if (words.length == 0) {
            return List.of();
        }
        MapSqlParameterSource params = VideoVisibility.bind(me.get(), now())
                .addValue("keywords", SqlArrays.textArray(words))
                .addValue("limit", page.get().size())
                .addValue("offset", page.get().offset());
        return jdbc.queryForList(SEARCH, params, String.class);
    }

    @Override
    public double getAverageViewRate(String bv) {
        return jdbc.queryForList(AVERAGE_VIEW_RATE, new MapSqlParameterSource("bv", bv), Double.class).stream()
                .findFirst()
                .orElse(-1.0);
    }

    @Override
    public Set<Integer> getHotspot(String bv) {
        MapSqlParameterSource params =
                new MapSqlParameterSource().addValue("bv", bv).addValue("chunk", HOTSPOT_CHUNK_SECONDS);
        return new HashSet<>(jdbc.queryForList(HOTSPOTS, params, Integer.class));
    }

    @Override
    public boolean reviewVideo(AuthInfo auth, String bv) {
        Optional<CurrentUser> me = authenticator.authenticate(auth);
        if (me.isEmpty() || !me.get().superuser() || bv == null) {
            return false;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("bv", bv)
                .addValue("me", me.get().mid())
                .addValue("now", now());
        return jdbc.update(REVIEW_VIDEO, params) == 1;
    }

    @Override
    public boolean coinVideo(AuthInfo auth, String bv) {
        Optional<CurrentUser> me = authenticator.authenticate(auth);
        if (me.isEmpty() || bv == null) {
            return false;
        }
        try {
            return jdbc.update(
                            DONATE_COIN, VideoVisibility.bind(me.get(), now()).addValue("bv", bv))
                    == 1;
        } catch (DuplicateKeyException e) {
            // A concurrent donation by the same user to the same video won; nothing was charged.
            return false;
        }
    }

    @Override
    public boolean likeVideo(AuthInfo auth, String bv) {
        return toggle(TOGGLE_LIKE, auth, bv);
    }

    @Override
    public boolean collectVideo(AuthInfo auth, String bv) {
        return toggle(TOGGLE_FAVORITE, auth, bv);
    }

    private boolean toggle(String sql, AuthInfo auth, String bv) {
        Optional<CurrentUser> me = authenticator.authenticate(auth);
        if (me.isEmpty() || bv == null) {
            return false;
        }
        MapSqlParameterSource params = VideoVisibility.bind(me.get(), now()).addValue("bv", bv);
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, params, Boolean.class));
    }

    /** The checks shared by postVideo and updateVideoInfo. */
    private static boolean isValid(PostVideoReq req, Timestamp now) {
        return req != null
                && !Strings.isNullOrEmpty(req.getTitle())
                && req.getDuration() >= MIN_DURATION_SECONDS
                && req.getPublicTime() != null
                && !req.getPublicTime().before(now);
    }

    /** Keywords are separated by spaces; runs of spaces do not produce empty keywords. */
    static String[] splitKeywords(String keywords) {
        return Arrays.stream(keywords.split(" ")).filter(w -> !w.isEmpty()).toArray(String[]::new);
    }

    private static String newBv() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(BV_PREFIX.length() + BV_RANDOM_LENGTH).append(BV_PREFIX);
        for (int i = 0; i < BV_RANDOM_LENGTH; i++) {
            sb.append(BV_ALPHABET.charAt(random.nextInt(BV_ALPHABET.length())));
        }
        return sb.toString();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }
}
