package io.sustc.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Compares the trigger-maintained tables with a recomputation from the source tables. */
final class DerivedTables {

    /** The view time sums may differ in the last bits because they are summed in another order. */
    private static final String STATS_MISMATCHES =
            """
            with expected as (
                select v.bv,
                       (select count(*) from viewers w where w.bv = v.bv) as viewers,
                       coalesce((select sum(view_time) from viewers w where w.bv = v.bv), 0) as view_time_sum,
                       (select count(*) from videolikes l where l.bv = v.bv) as likes,
                       (select count(*) from coins c where c.bv = v.bv) as coins,
                       (select count(*) from favorites f where f.bv = v.bv) as favorites,
                       (select count(*) from danmus d where d.bv = v.bv) as danmus
                from videos v
            )
            select coalesce(e.bv, s.bv) || ': expected ' || coalesce(e::text, 'no row') || ', found '
                   || coalesce(s::text, 'no row')
            from expected e
            full join video_stats s on s.bv = e.bv
            where e.bv is null or s.bv is null
               or (e.viewers, e.likes, e.coins, e.favorites, e.danmus)
                  <> (s.viewers, s.likes, s.coins, s.favorites, s.danmus)
               or abs(e.view_time_sum - s.view_time_sum) > 1e-6 * greatest(1, abs(e.view_time_sum))
            order by 1
            """;

    private static final String COVIEW_MISMATCHES =
            """
            with expected as (
                select a.bv, b.bv as other_bv, count(*)::integer as viewers
                from viewers a
                join viewers b on b.mid = a.mid and b.bv <> a.bv
                group by a.bv, b.bv
            )
            select coalesce(e.bv, c.bv) || '/' || coalesce(e.other_bv, c.other_bv) || ': expected '
                   || coalesce(e.viewers::text, 'no row') || ', found ' || coalesce(c.viewers::text, 'no row')
            from expected e
            full join video_coviews c on c.bv = e.bv and c.other_bv = e.other_bv
            where e.viewers is distinct from c.viewers
            order by 1
            """;

    private DerivedTables() {}

    static void assertConsistent(JdbcTemplate jdbc) {
        List<String> stats = jdbc.queryForList(STATS_MISMATCHES, String.class);
        List<String> coviews = jdbc.queryForList(COVIEW_MISMATCHES, String.class);
        assertThat(stats)
                .as("video_stats rows that differ from a recomputation")
                .isEmpty();
        assertThat(coviews)
                .as("video_coviews rows that differ from a recomputation")
                .isEmpty();
    }
}
