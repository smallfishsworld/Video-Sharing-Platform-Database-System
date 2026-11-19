-- Server-side time of deleting accounts, without EXPLAIN: a sample of the users who own no video
-- (every 1,000th of them by mid, plus mid 168, the account that delete-costs.sql deletes), each
-- deleted in a transaction of its own and rolled back, in two passes. The time is clock_timestamp()
-- around the DELETE in PL/pgSQL, so it covers the statement with its foreign-key cascades and
-- triggers, but no commit and no round trip. Needs the benchmark data imported by this version.
--
--   psql -X -v ON_ERROR_STOP=1 -f docs/explain/delete-account-sample.sql > docs/explain/delete-account-sample.after.txt
create temporary table acct_sample as
select u.mid,
       (select count(*) from viewers w where w.mid = u.mid) as views,
       (select count(*) from videolikes l where l.mid = u.mid) as likes,
       (select count(*) from coins c where c.mid = u.mid) as coins,
       (select count(*) from favorites f where f.mid = u.mid) as favorites,
       (select count(*) from followings f where f.follower_mid = u.mid or f.followee_mid = u.mid) as follow_rows
from (select mid, row_number() over (order by mid) as n
      from users u
      where not exists (select 1 from videos v where v.owner_mid = u.mid)) u
where u.n % 1000 = 1 or u.mid = 168;
create temporary table acct_timings (pass integer, mid bigint, ms double precision);
do $$
declare
    mids bigint[] := array(select mid from acct_sample order by mid);
    m bigint;
    t0 timestamptz;
    ms double precision;
begin
    for pass in 1..2 loop
        foreach m in array mids loop
            t0 := clock_timestamp();
            delete from users where mid = m;
            ms := extract(epoch from clock_timestamp() - t0) * 1000;
            rollback;
            insert into acct_timings values (pass, m, ms);
            commit;
        end loop;
    end loop;
end $$;
select s.mid, s.views, s.views * (s.views - 1) as coview_changes, s.likes, s.coins, s.favorites, s.follow_rows,
       round(t1.ms::numeric, 2) as pass1_ms, round(t2.ms::numeric, 2) as pass2_ms
from acct_sample s
join acct_timings t1 on t1.mid = s.mid and t1.pass = 1
join acct_timings t2 on t2.mid = s.mid and t2.pass = 2
order by s.views, s.mid;
select t.pass, count(*) as users,
       percentile_cont(0.5) within group (order by s.views) as median_views,
       round(percentile_cont(0.5) within group (order by t.ms)::numeric, 2) as median_ms,
       round(min(t.ms)::numeric, 2) as min_ms, round(max(t.ms)::numeric, 2) as max_ms,
       round(corr(t.ms, s.views * (s.views - 1))::numeric, 3) as corr_ms_coview_changes,
       round((regr_slope(t.ms, s.views * (s.views - 1)) * 1000)::numeric, 2) as us_per_coview_change,
       round(regr_intercept(t.ms, s.views * (s.views - 1))::numeric, 2) as intercept_ms
from acct_sample s join acct_timings t on t.mid = s.mid
group by t.pass order by t.pass;
