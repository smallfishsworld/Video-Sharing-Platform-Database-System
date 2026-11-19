-- Lookup tables that let pgbench pick a random user / published video by a dense integer.
-- Created in the schema given by search_path; dropped with it.
drop table if exists bench_users, bench_videos;
create table bench_users as select row_number() over (order by mid) as i, mid, password from users;
create unique index on bench_users (i);
analyze bench_users;
