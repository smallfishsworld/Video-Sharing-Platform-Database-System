-- EXPLAIN (ANALYZE, BUFFERS) of the two deletes that cascade, on the benchmark data as this version
-- imports it: the video with the median number of views (the lower median of the 44), and the account
-- of the user with the lowest mid among those who own no videos and have the median number of views.
-- Five times each in one session, each rolled back. The first video delete is the session's first
-- statement that fires the triggers, so it includes compiling their PL/pgSQL functions; the account
-- deletes run after the video deletes. The "Trigger" lines give
-- the time of each foreign-key cascade ("for constraint ...") and of each derived-table trigger
-- (derived_*); trigger-times.awk adds them up per execution.
--
--   psql -X -v ON_ERROR_STOP=1 -f docs/explain/delete-costs.sql > docs/explain/delete-costs.after.txt
--   awk -f docs/explain/trigger-times.awk docs/explain/delete-costs.after.txt
select s.bv as vbv, s.viewers as vviews
from video_stats s
order by s.viewers, s.bv
offset (select (count(*) - 1) / 2 from video_stats)
limit 1 \gset
with per_user as (
    select mid, count(*) as n from viewers group by mid
),
median as (
    select n from per_user order by n offset (select (count(*) - 1) / 2 from per_user) limit 1
)
select p.mid as umid, p.n as uviews
from per_user p, median m
where p.n = m.n
  and not exists (select 1 from videos v where v.owner_mid = p.mid)
order by p.mid
limit 1 \gset
select :'vbv' as video, :vviews as views,
       (select count(*) from danmus where bv = :'vbv') as danmus,
       (select count(*) from videolikes where bv = :'vbv') as likes,
       (select count(*) from coins where bv = :'vbv') as coins,
       (select count(*) from favorites where bv = :'vbv') as favorites,
       (select count(*) from video_coviews where bv = :'vbv' or other_bv = :'vbv') as coview_rows;
select :umid as account, :uviews as views,
       (select count(*) from danmus where mid = :umid) as danmus,
       (select count(*) from videolikes where mid = :umid) as likes,
       (select count(*) from coins where mid = :umid) as coins,
       (select count(*) from favorites where mid = :umid) as favorites,
       (select count(*) from followings where follower_mid = :umid or followee_mid = :umid) as follow_rows;
\set delvideo 'explain (analyze, buffers) delete from videos where bv = ' :'vbv'
\set delaccount 'explain (analyze, buffers) delete from users where mid = ' :umid
\echo == delete video, execution 1 of 5 (first in this session)
begin;
:delvideo;
rollback;
\echo == delete video, execution 2 of 5
begin;
:delvideo;
rollback;
\echo == delete video, execution 3 of 5
begin;
:delvideo;
rollback;
\echo == delete video, execution 4 of 5
begin;
:delvideo;
rollback;
\echo == delete video, execution 5 of 5
begin;
:delvideo;
rollback;
\echo == delete account, execution 1 of 5 (first in this session)
begin;
:delaccount;
rollback;
\echo == delete account, execution 2 of 5
begin;
:delaccount;
rollback;
\echo == delete account, execution 3 of 5
begin;
:delaccount;
rollback;
\echo == delete account, execution 4 of 5
begin;
:delaccount;
rollback;
\echo == delete account, execution 5 of 5
begin;
:delaccount;
rollback;
