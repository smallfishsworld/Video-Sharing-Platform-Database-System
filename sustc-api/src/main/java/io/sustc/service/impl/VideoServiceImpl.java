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
import io.sustc.service.impl.support.Writes;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
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
     * The owner name is copied from the users table. ON CONFLICT DO NOTHING covers both unique
     * keys (bv and (owner, title)), so the statement never raises a unique violation. When no row
     * is inserted, {@code refused} tells a taken title or a deleted owner (the request fails) from
     * a bv collision (the caller retries with another bv).
     */
    private static final String INSERT_VIDEO =
            """
            with added as (
                insert into videos (bv, title, owner_mid, owner_name, commit_time, public_time, duration, description)
                select :bv, :title, u.mid, u.name, :now, :publicTime, :duration, :description
                from users u
                where u.mid = :me
                on conflict do nothing
                returning bv
            )
            select (select bv from added) as bv,
                   exists (select 1 from videos o where o.owner_mid = :me and o.title = :title)
                       or not exists (select 1 from users u where u.mid = :me) as refused
            """;

    /** Foreign keys cascade to every interaction with the video, so one statement suffices. */
    private static final String DELETE_VIDEO = "delete from videos where bv = :bv and (:superuser or owner_mid = :me)";

    /**
     * Applies an owner's edit if the duration is unchanged, something actually changed and the
     * owner has no other video with the new title. Any edit sends the video back for review; the
     * result says whether it had been reviewed.
     *
     * <p>{@code old} locks the row before reading it. Under READ COMMITTED a locking read returns
     * the latest committed version, so two concurrent edits of one video are applied one after
     * the other and each reports the review state that it replaced.
     */
    private static final String UPDATE_VIDEO =
            """
            with old as (
                select bv, review_time from videos where bv = :bv and owner_mid = :me for update
            )
            update videos v
            set title = :title, description = :description, public_time = :publicTime,
                review_time = null, reviewer = null
            from old
            where v.bv = old.bv
              and v.duration = :duration
              and (v.title, v.description, v.public_time) is distinct from (:title, :description, :publicTime)
              and not exists (select 1 from videos o where o.owner_mid = :me and o.title = :title and o.bv <> v.bv)
            returning old.review_time is not null
            """;

    /**
     * Relevance is the total number of non-overlapping, case-insensitive occurrences of every
     * keyword in the title, description and owner name. fold_case (sql/build.sql) lowercases with
     * an explicit ICU collation, so matching does not depend on the database locale. Ties are
     * broken by view count (kept in video_stats), then by bv in byte order (collate "C"), so that
     * pages are stable and, for the ASCII bvs, ordered like Java strings on any server.
     */
    private static final String SEARCH =
            """
            select v.bv
            from videos v
            join video_stats s on s.bv = v.bv
            cross join lateral (
                select sum(count_occurrences(fold_case(v.title), k.word)
                         + count_occurrences(fold_case(coalesce(v.description, '')), k.word)
                         + count_occurrences(fold_case(v.owner_name), k.word)) as relevance
                from (select fold_case(w) as word from unnest(:keywords) as w) k
            ) r
            where r.relevance > 0
              and %s
            order by r.relevance desc, s.viewers desc, v.bv collate "C"
            limit :limit offset :offset
            """
                    .formatted(VideoVisibility.VISIBLE_TO_ME);

    /** The mean view time over the duration, from the counters in video_stats. */
    private static final String AVERAGE_VIEW_RATE =
            """
            select s.view_time_sum / s.viewers / v.duration
            from videos v
            join video_stats s on s.bv = v.bv
            where v.bv = :bv and s.viewers > 0
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

    /**
     * Like/collect toggle: removes the row if present, inserts it otherwise; returns the new state.
     * The toggle runs after {@link Writes#LOCK_ACTING_USER}, so concurrent toggles by one user are
     * applied one after the other.
     */
    private static final String TOGGLE = Writes.LOCK_ACTING_USER
            + """
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
    private final DataSource dataSource;
    private final Authenticator authenticator;
    private final Clock clock;

    public VideoServiceImpl(NamedParameterJdbcTemplate jdbc, Authenticator authenticator, Clock clock) {
        this.jdbc = jdbc;
        this.dataSource = Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource());
        this.authenticator = authenticator;
        this.clock = clock;
    }

    /** The result of {@link #INSERT_VIDEO}. */
    private record Inserted(String bv, boolean refused) {
        static final Inserted NOT_DONE = new Inserted(null, true);
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
            Inserted inserted = Writes.run(
                    dataSource,
                    () -> jdbc.queryForObject(
                            INSERT_VIDEO, params, (rs, i) -> new Inserted(rs.getString(1), rs.getBoolean(2))),
                    Inserted.NOT_DONE);
            if (inserted.bv() != null || inserted.refused()) {
                return inserted.bv();
            }
            log.debug("Generated bv collided with an existing video (attempt {})", attempt);
        }
        throw new IllegalStateException("Could not generate an unused bv in " + MAX_BV_ATTEMPTS + " attempts");
    }

    @Override
    public boolean deleteVideo(AuthInfo auth, String bv) {
        Optional<CurrentUser> me = authenticator.authenticate(auth);
        if (me.isEmpty() || bv == null) {
            return false;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("bv", bv)
                .addValue("me", me.get().mid())
                .addValue("superuser", me.get().superuser());
        return Writes.run(dataSource, () -> jdbc.update(DELETE_VIDEO, params) == 1, false);
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
        // A unique violation is left only for a race with a concurrent write of the same title.
        return Writes.run(
                dataSource,
                () -> {
                    List<Boolean> wasReviewed = jdbc.queryForList(UPDATE_VIDEO, params, Boolean.class);
                    return !wasReviewed.isEmpty() && wasReviewed.get(0);
                },
                false);
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
        return Writes.run(dataSource, () -> jdbc.update(REVIEW_VIDEO, params) == 1, false);
    }

    @Override
    public boolean coinVideo(AuthInfo auth, String bv) {
        Optional<CurrentUser> me = authenticator.authenticate(auth);
        if (me.isEmpty() || bv == null) {
            return false;
        }
        MapSqlParameterSource params = VideoVisibility.bind(me.get(), now()).addValue("bv", bv);
        // A unique violation means that a concurrent donation by the same user to the same video
        // won; the statement rolled back, so nothing was charged.
        return Writes.run(dataSource, () -> jdbc.update(DONATE_COIN, params) == 1, false);
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
        return Writes.run(dataSource, () -> jdbc.execute(sql, params, Writes.RESULT_AFTER_LOCK), false);
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
