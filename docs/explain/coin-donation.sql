-- EXPLAIN (ANALYZE, BUFFERS) of one coin donation that does happen: a user with coins left who has
-- not donated to the video and does not own it. Five times in one session, each rolled back, so the
-- first line is the session's first execution (PL/pgSQL functions of the triggers not yet compiled,
-- plans not cached) and the other four are warm. Needs the benchmark data imported by this version.
--
--   psql -X -v ON_ERROR_STOP=1 -f docs/explain/coin-donation.sql > docs/explain/coin-donation.after.txt
\set bv '''BV1PK4y1b7dt'''
select u.mid as me
from users u
where u.coin > 0
  and u.mid <> (select owner_mid from videos where bv = :bv)
  and not exists (select 1 from coins c where c.bv = :bv and c.mid = u.mid)
order by u.mid
limit 1 \gset
select :me as donor, (select coin from users where mid = :me) as coins_before, :bv as video;
\set donate 'explain (analyze, buffers) with target as (select v.bv from videos v where v.bv = ' :bv ' and v.owner_mid <> ' :me ' and (false or v.owner_mid = ' :me ' or (v.review_time is not null and (v.public_time is null or v.public_time <= ''2023-12-30 12:00:00''))) and not exists (select 1 from coins c where c.bv = v.bv and c.mid = ' :me ')), charged as (update users set coin = coin - 1 where mid = ' :me ' and coin > 0 and exists (select 1 from target) returning mid) insert into coins (bv, mid) select target.bv, charged.mid from target, charged'
\echo == execution 1 of 5 (first in this session)
begin;
:donate;
rollback;
\echo == execution 2 of 5
begin;
:donate;
rollback;
\echo == execution 3 of 5
begin;
:donate;
rollback;
\echo == execution 4 of 5
begin;
:donate;
rollback;
\echo == execution 5 of 5
begin;
:donate;
rollback;
