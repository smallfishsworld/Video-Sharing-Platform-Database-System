drop table if exists bench_videos;
create table bench_videos as
    select row_number() over (order by bv) as i, bv, duration from videos where review_time is not null;
create unique index on bench_videos (i);
analyze bench_videos;
