-- Planning and execution time of the hotspot query (step 4) and the danmu display query (step 9) in
-- a fresh session, three times each: the first execution in a backend also fills that backend's
-- catalog caches, the later ones find them filled. EXPLAIN (ANALYZE, TIMING OFF), so that per-node
-- timing does not add to the execution time. The video is the one with the most danmus; the display
-- range is its whole duration. Needs the benchmark data imported by this version.
--
--   psql -X -v ON_ERROR_STOP=1 -f docs/explain/first-call.sql > docs/explain/first-call.after.txt
select bv as vbv, count(*) as vdanmus from danmus group by bv order by count(*) desc, bv limit 1 \gset
select duration as vduration from videos where bv = :'vbv' \gset
select :'vbv' as video, :vdanmus as danmus, :vduration as duration;
\set hotspots 'explain (analyze, timing off) select chunk from (select floor(time / 10)::integer as chunk, rank() over (order by count(*) desc) as rnk from danmus where bv = ' :'vbv' ' group by chunk) ranked where rnk = 1'
\set display 'explain (analyze, timing off) select array(select d.id from danmus d where d.bv = v.bv and d.time between 0 and ' :vduration ' order by d.time, d.id) from videos v where v.bv = ' :'vbv' ' and ' :vduration ' <= v.duration and v.review_time is not null and (v.public_time is null or v.public_time <= ''2023-12-30 12:00:00'')'
\echo == hotspots, execution 1 of 3 (first in this session)
:hotspots;
\echo == hotspots, execution 2 of 3
:hotspots;
\echo == hotspots, execution 3 of 3
:hotspots;
\echo == display, execution 1 of 3 (first of this statement in this session)
:display;
\echo == display, execution 2 of 3
:display;
\echo == display, execution 3 of 3
:display;
