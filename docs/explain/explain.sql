-- EXPLAIN (ANALYZE, BUFFERS) of the most expensive benchmark queries, before and after.
--
-- "before" runs the queries of the original implementation (commit 2e811bd) on schema
-- "original": that commit's sql/build.sql (tables, indexes, the qmin function) holding the rows
-- of the benchmark's import, analyzed. "after" runs the current queries (copied from the
-- *ServiceImpl classes, with the parameters inlined) on schema "public", freshly loaded by the
-- current importer. Both contain the same 44 videos, 37,881 users, 874,856 views and 5,958,770
-- follow relations.
-- The inputs are cases of the benchmark data. Every query runs once to warm the cache and is
-- then measured. Run it with scripts/explain_plans.sh, which writes one file per query.
\set ON_ERROR_STOP on
\pset pager off
set jit = on;

---------------------------------------------------------------------------- similar videos
\o docs/explain/similar-videos.before.txt
set search_path = original;
select a.bv,count(b.mid) as cnt from viewers a inner join viewers b on a.mid=b.mid where b.bv='BV1PK4y1b7dt' and a.bv !='BV1PK4y1b7dt' group by a.bv order by cnt desc limit 5;
explain (analyze, buffers)
select a.bv,count(b.mid) as cnt from viewers a inner join viewers b on a.mid=b.mid where b.bv='BV1PK4y1b7dt' and a.bv !='BV1PK4y1b7dt' group by a.bv order by cnt desc limit 5;

\o docs/explain/similar-videos.after.txt
set search_path = public;
select array(select c.other_bv from video_coviews c where c.bv = v.bv order by c.viewers desc, c.other_bv limit 5) from videos v where v.bv = 'BV1PK4y1b7dt';
explain (analyze, buffers)
select array(
    select c.other_bv
    from video_coviews c
    where c.bv = v.bv
    order by c.viewers desc, c.other_bv
    limit 5)
from videos v
where v.bv = 'BV1PK4y1b7dt';

---------------------------------------------------------------------- general recommendations
\o docs/explain/general-rec.before.txt
set search_path = original;
\set general_before 'select a.bv, case views when 0 then 0 else qmin(cast(b.lik as numeric)/cast(a.views as numeric),1.0)+ qmin(cast(c.coi as numeric)/cast(a.views as numeric),1.0)+ qmin(cast(d.fav as numeric)/cast(a.views as numeric),1.0)+ qmin(cast(e.dan as numeric)/cast(a.views as numeric),1.0)+ qmin(cast(a.avgviewtime/a.duration as numeric),1.0) end as score from (select v.bv,v.duration ,count(r.mid) as views,sum(viewtime)/count(*) as avgviewtime from videos v left join viewers r on v.bv=r.bv group by v.bv,v.duration) a inner join (select v.bv,count(r.mid) as lik from videos v left join videolikes r on v.bv=r.bv group by v.bv) b on a.bv=b.bv inner join (select v.bv,count(r.mid) as coi from videos v left join coins r on v.bv=r.bv group by v.bv) c on a.bv=c.bv inner join (select v.bv,count(r.mid) as fav from videos v left join favorites r on v.bv=r.bv group by v.bv)d on a.bv=d.bv inner join (select v.bv,count(r.mid) as dan from videos v left join danmus r on v.bv=r.bv group by v.bv)e on a.bv=e.bv order by score desc  offset 0 limit 10'
:general_before;
explain (analyze, buffers) :general_before;

\o docs/explain/general-rec.after.txt
set search_path = public;
\set general_after 'select v.bv from videos v join video_stats s on s.bv = v.bv order by case when s.viewers = 0 then 0 else least(s.likes::double precision / s.viewers, 1) + least(s.coins::double precision / s.viewers, 1) + least(s.favorites::double precision / s.viewers, 1) + s.danmus::double precision / s.viewers + s.view_time_sum / s.viewers / v.duration end desc, v.bv limit 10 offset 0'
:general_after;
explain (analyze, buffers) :general_after;

------------------------------------------------------------------- user recommendations
-- mid 422017, page size 8, page 1: of the 22 benchmark cases with valid credentials (27 cases in
-- all) the one whose friends (14 of them) watched the most videos. 13 of the 22 have no friends;
-- for those the original implementation ran its general-recommendation query (see
-- general-rec.before.txt). The original query has no LIMIT: it returns every candidate and the
-- Java code drops watched videos and pages.
\o docs/explain/user-rec.before.txt
set search_path = original;
\set user_before 'select v.bv,t.cnt,u.level,u.mid,u.name,v.publictime,vie.viewtime,vie.mid as chkmid from videos v inner join (select r.bv,count(f.mid2) as cnt from (select a.mid2 from followings a inner join  followings b on a.mid1=b.mid2 and a.mid2=b.mid1 where a.mid1=422017) f inner join viewers r on f.mid2=r.mid group by r.bv) t on v.bv=t.bv inner join users u on v.ownermid=u.mid left join viewers vie on vie.mid=422017 and v.bv=vie.bv order by t.cnt desc,u.level desc,v.publictime desc'
:user_before;
explain (analyze, buffers) :user_before;

\o docs/explain/user-rec.after.txt
set search_path = public;
\set user_after 'with friends as (select f.followee_mid as mid from followings f join followings back on back.follower_mid = f.followee_mid and back.followee_mid = f.follower_mid where f.follower_mid = 422017) select w.bv from friends fr join viewers w on w.mid = fr.mid join videos v on v.bv = w.bv join users owner on owner.mid = v.owner_mid where not exists (select 1 from viewers mine where mine.bv = w.bv and mine.mid = 422017) group by w.bv, owner.level, v.public_time order by count(*) desc, owner.level desc, v.public_time desc nulls last, w.bv limit 8 offset 0'
:user_after;
explain (analyze, buffers) :user_after;

----------------------------------------------------------------- friend recommendations
-- Two of the 133 benchmark cases with valid credentials (142 in all): mid 1727180 (page size 6,
-- page 5) follows 43 accounts, the median; mid 882400 (page size 8, page 3) follows 1,762, the
-- most.
\o docs/explain/friend-rec.before.txt
set search_path = original;
\set friend_before_median 'select s.mid1,s.cnt,u.level from (select t.mid1,h.mid2 as chkmid,t.cnt from (select b.mid1,count(*) as cnt from followings a inner join followings b on a.mid2=b.mid2 where a.mid1=1727180 and b.mid1!=1727180 group by b.mid1) t left join followings h on h.mid1=1727180 and h.mid2=t.mid1) s inner join  users u on s.mid1=u.mid where s.chkmid is null order by s.cnt desc,u.level desc,s.mid1  offset 24 limit 6'
\set friend_before 'select s.mid1,s.cnt,u.level from (select t.mid1,h.mid2 as chkmid,t.cnt from (select b.mid1,count(*) as cnt from followings a inner join followings b on a.mid2=b.mid2 where a.mid1=882400 and b.mid1!=882400 group by b.mid1) t left join followings h on h.mid1=882400 and h.mid2=t.mid1) s inner join  users u on s.mid1=u.mid where s.chkmid is null order by s.cnt desc,u.level desc,s.mid1  offset 16 limit 8'
:friend_before_median;
explain (analyze, buffers) :friend_before_median;
:friend_before;
explain (analyze, buffers) :friend_before;

-- The same two queries on tables that were never analyzed (schema original_nostats, built like
-- the original import: indexes first, then the rows, no ANALYZE), as in a benchmark run in which
-- autovacuum has not analyzed followings yet when step 8 starts.
\o docs/explain/friend-rec.before-nostats.txt
set search_path = original_nostats;
:friend_before_median;
explain (analyze, buffers) :friend_before_median;
:friend_before;
explain (analyze, buffers) :friend_before;

\o docs/explain/friend-rec.after.txt
set search_path = public;
\set friend_after_median 'select c.mid from (select o.follower_mid as mid, count(*) as common from followings mine join followings o on o.followee_mid = mine.followee_mid where mine.follower_mid = 1727180 group by o.follower_mid) c join users u on u.mid = c.mid where c.mid <> 1727180 and not exists (select 1 from followings x where x.follower_mid = 1727180 and x.followee_mid = c.mid) order by c.common desc, u.level desc, c.mid limit 6 offset 24'
\set friend_after 'select c.mid from (select o.follower_mid as mid, count(*) as common from followings mine join followings o on o.followee_mid = mine.followee_mid where mine.follower_mid = 882400 group by o.follower_mid) c join users u on u.mid = c.mid where c.mid <> 882400 and not exists (select 1 from followings x where x.follower_mid = 882400 and x.followee_mid = c.mid) order by c.common desc, u.level desc, c.mid limit 8 offset 16'
:friend_after_median;
explain (analyze, buffers) :friend_after_median;
:friend_after;
explain (analyze, buffers) :friend_after;

--------------------------------------------------------------------------------- search
-- keywords "周杰伦 MV" searched by regular user 200267 at the pinned benchmark time
\o docs/explain/search.before.txt
set search_path = original;
\set search_before 'with tmp as ( select distinct t.bv,t.cnt,count(*) as views from (select bv, (array_length(regexp_split_to_array(lower(title),''周杰伦''),1)-1)+(array_length(regexp_split_to_array(lower(description),''周杰伦''),1)-1)+(array_length(regexp_split_to_array(lower(ownername),''周杰伦''),1)-1)+(array_length(regexp_split_to_array(lower(title),''mv''),1)-1)+(array_length(regexp_split_to_array(lower(description),''mv''),1)-1)+(array_length(regexp_split_to_array(lower(ownername),''mv''),1)-1) as cnt from videos  where (publictime < ''2023-12-30 12:00:00'' or ownermid = 200267 ) order by bv) t left join viewers r on t.bv=r.bv where t.cnt>0 group by t.bv,t.cnt)    select * from tmp order by cnt desc,views desc offset 0 limit 10'
:search_before;
explain (analyze, buffers) :search_before;

\o docs/explain/search.after.txt
set search_path = public;
\set search_after 'select v.bv from videos v join video_stats s on s.bv = v.bv cross join lateral (select sum(count_occurrences(lower(v.title), k.word) + count_occurrences(lower(coalesce(v.description, '''')), k.word) + count_occurrences(lower(v.owner_name), k.word)) as relevance from (select lower(w) as word from unnest(''{周杰伦,MV}''::text[]) as w) k) r where r.relevance > 0 and (false or v.owner_mid = 200267 or (v.review_time is not null and (v.public_time is null or v.public_time <= ''2023-12-30 12:00:00''))) order by r.relevance desc, s.viewers desc, v.bv limit 10 offset 0'
:search_after;
explain (analyze, buffers) :search_after;

------------------------------------------------------------- write path (rolled back)
-- What one write costs after the change: the statement itself, the foreign-key checks and
-- the statement-level triggers that maintain video_stats / video_coviews ("Trigger" lines).
-- There is no "before" file: the original implementation spreads each write over several
-- autocommit statements (lookups, then INSERT or DELETE), so its cost is measured end to end
-- with pgbench instead (scripts/write_latency.sh, docs/experiments/write-latency.txt).
\o docs/explain/write-path.after.txt
set search_path = public;
begin;
-- like toggle by user 200267 on a published video it has not liked
explain (analyze, buffers)
with target as (
    select v.bv from videos v
    where v.bv = 'BV1PK4y1b7dt' and v.owner_mid <> 200267
      and (false or v.owner_mid = 200267 or (v.review_time is not null and (v.public_time is null or v.public_time <= '2023-12-30 12:00:00')))
),
removed as (
    delete from videolikes t using target where t.bv = target.bv and t.mid = 200267 returning t.bv
),
added as (
    insert into videolikes (bv, mid) select bv, 200267 from target
    where not exists (select 1 from removed) on conflict do nothing returning bv
)
select exists (select 1 from added);
rollback;
begin;
-- coin donation (atomic: the coin is only charged if the donation row is inserted)
explain (analyze, buffers)
with target as (
    select v.bv from videos v
    where v.bv = 'BV1PK4y1b7dt' and v.owner_mid <> 200267
      and (false or v.owner_mid = 200267 or (v.review_time is not null and (v.public_time is null or v.public_time <= '2023-12-30 12:00:00')))
      and not exists (select 1 from coins c where c.bv = v.bv and c.mid = 200267)
),
charged as (
    update users set coin = coin - 1
    where mid = 200267 and coin > 0 and exists (select 1 from target)
    returning mid
)
insert into coins (bv, mid) select target.bv, charged.mid from target, charged;
rollback;
begin;
-- deleting a video with 27,136 views cascades to every interaction table
explain (analyze, buffers)
delete from videos where bv = 'BV1mG4y1z761';
rollback;
\o
