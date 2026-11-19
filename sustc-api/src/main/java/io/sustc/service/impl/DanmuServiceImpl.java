package io.sustc.service.impl;

import io.sustc.dto.AuthInfo;
import io.sustc.service.DanmuService;
import io.sustc.service.impl.auth.Authenticator;
import io.sustc.service.impl.auth.CurrentUser;
import io.sustc.service.impl.support.SqlArrays;
import io.sustc.service.impl.support.Strings;
import io.sustc.service.impl.support.VideoVisibility;
import io.sustc.service.impl.support.Writes;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DanmuServiceImpl implements DanmuService {

    private static final long INVALID_ID = -1;

    /**
     * Only viewers of a published video can send danmus to it, at a time within the video. The
     * owner and superusers get no exception here: an unpublished video has no audience yet.
     */
    private static final String SEND =
            """
            insert into danmus (bv, mid, time, content, post_time)
            select v.bv, :me, :time, :content, :now
            from videos v
            where v.bv = :bv
              and %s
              and :time >= 0 and :time <= v.duration
              and exists (select 1 from viewers w where w.bv = v.bv and w.mid = :me)
            returning id
            """
                    .formatted(VideoVisibility.PUBLISHED);

    /**
     * Returns one row (the ids, possibly none) if the video is published and the range ends
     * within it, and no row otherwise, so that one round trip tells "no danmus" from "invalid".
     */
    private static final String DISPLAY =
            """
            select array(
                select d.id from danmus d
                where d.bv = v.bv and d.time between :start and :end
                order by d.time, d.id)
            from videos v
            where v.bv = :bv and :end <= v.duration and %s
            """
                    .formatted(VideoVisibility.PUBLISHED);

    /** Like {@link #DISPLAY}, keeping only the earliest posted danmu for each content. */
    private static final String DISPLAY_DISTINCT =
            """
            select array(
                select f.id
                from (select distinct on (d.content) d.id, d.time
                      from danmus d
                      where d.bv = v.bv and d.time between :start and :end
                      order by d.content, d.post_time, d.id) f
                order by f.time, f.id)
            from videos v
            where v.bv = :bv and :end <= v.duration and %s
            """
                    .formatted(VideoVisibility.PUBLISHED);

    /** Toggles the like of a danmu whose video the user has watched; returns the new state. */
    private static final String TOGGLE_LIKE =
            """
            with target as (
                select d.id from danmus d
                where d.id = :id and exists (select 1 from viewers w where w.bv = d.bv and w.mid = :me)
            ),
            removed as (
                delete from danmulikes l
                using target
                where l.danmu_id = target.id and l.mid = :me
                returning l.danmu_id
            ),
            added as (
                insert into danmulikes (danmu_id, mid)
                select id, :me from target
                where not exists (select 1 from removed)
                on conflict do nothing
                returning danmu_id
            )
            select exists (select 1 from added)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final DataSource dataSource;
    private final Authenticator authenticator;
    private final Clock clock;

    public DanmuServiceImpl(NamedParameterJdbcTemplate jdbc, Authenticator authenticator, Clock clock) {
        this.jdbc = jdbc;
        this.dataSource = Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource());
        this.authenticator = authenticator;
        this.clock = clock;
    }

    @Override
    public long sendDanmu(AuthInfo auth, String bv, String content, float time) {
        Optional<CurrentUser> me = authenticator.authenticate(auth);
        if (me.isEmpty() || bv == null || Strings.isNullOrEmpty(content) || Float.isNaN(time)) {
            return INVALID_ID;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("bv", bv)
                .addValue("me", me.get().mid())
                .addValue("time", (double) time)
                .addValue("content", content)
                .addValue("now", now());
        return Writes.run(
                dataSource,
                () -> jdbc.queryForList(SEND, params, Long.class).stream()
                        .findFirst()
                        .orElse(INVALID_ID),
                INVALID_ID);
    }

    @Override
    public List<Long> displayDanmu(String bv, float timeStart, float timeEnd, boolean filter) {
        if (bv == null || Float.isNaN(timeStart) || Float.isNaN(timeEnd) || timeStart < 0 || timeStart > timeEnd) {
            return null;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("bv", bv)
                .addValue("start", (double) timeStart)
                .addValue("end", (double) timeEnd)
                .addValue("now", now());
        List<List<Long>> rows = jdbc.query(
                filter ? DISPLAY_DISTINCT : DISPLAY, params, (rs, i) -> SqlArrays.toLongList(rs.getArray(1)));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public boolean likeDanmu(AuthInfo auth, long id) {
        Optional<CurrentUser> me = authenticator.authenticate(auth);
        if (me.isEmpty()) {
            return false;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("me", me.get().mid());
        return Boolean.TRUE.equals(jdbc.queryForObject(TOGGLE_LIKE, params, Boolean.class));
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }
}
