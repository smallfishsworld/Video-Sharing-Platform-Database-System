-- Plans of the co-view deletion in derived_videos (sql/build.sql), before and after ac3a0dc.
--
--   psql -X -q -d SCRATCH_DATABASE -f docs/experiments/coview-delete-plan.sql
--
-- Builds video_coviews as sql/build.sql defines it, in a schema of its own (coview_plan, dropped at
-- the end), with 44 videos whose co-view graph is complete (1,892 rows, as in the benchmark data)
-- and 300 videos without views. A statement trigger on videos EXPLAINs (ANALYZE, no timing) the
-- lock statement in both forms with the deleted videos as its transition table; each deletion is
-- rolled back. Plans only: nothing here is timed.
drop schema if exists coview_plan cascade;
create schema coview_plan;
set search_path = coview_plan;
create table videos (bv varchar primary key);
insert into videos select 'BV' || a from generate_series(1, 44) a;
insert into videos select 'BVnew' || a from generate_series(1, 300) a;
create table video_coviews (
    bv       varchar not null,
    other_bv varchar not null,
    viewers  integer not null check (viewers > 0),
    primary key (bv, other_bv)
);
create index video_coviews_other_bv_idx on video_coviews (other_bv);
insert into video_coviews
select 'BV' || a, 'BV' || b, 1 + (a * b) % 50 from generate_series(1, 44) a, generate_series(1, 44) b where a <> b;
analyze videos;
analyze video_coviews;
create function show_plans() returns trigger language plpgsql as
$$
declare
    r record;
begin
    raise notice '-- % deleted video(s); 1717da9: bv in (...) or other_bv in (...)', (select count(*) from old_rows);
    for r in execute 'explain (analyze, costs off, timing off, summary off)
                      select from coview_plan.video_coviews c
                      where c.bv in (select bv from old_rows) or c.other_bv in (select bv from old_rows)
                      order by c.bv, c.other_bv for update of c' loop
        raise notice '%', r."QUERY PLAN";
    end loop;
    raise notice '-- ac3a0dc: join old_rows o on c.bv = o.bv or c.other_bv = o.bv';
    for r in execute 'explain (analyze, costs off, timing off, summary off)
                      select from coview_plan.video_coviews c
                      join old_rows o on c.bv = o.bv or c.other_bv = o.bv
                      order by c.bv, c.other_bv for update of c' loop
        raise notice '%', r."QUERY PLAN";
    end loop;
    return null;
end
$$;
create trigger show_plans after delete on videos referencing old table as old_rows
    for each statement execute function show_plans();
\echo == a video without co-view rows
begin;
delete from videos where bv = 'BVnew7';
rollback;
\echo == two co-viewed videos
begin;
delete from videos where bv in ('BV7', 'BV9');
rollback;
\echo == no video (what the cascade from users into videos does for a user without videos)
begin;
delete from videos where bv = 'none';
rollback;
drop schema coview_plan cascade;
