--show default_transaction_isolation;
set default_transaction_isolation='Read Committed';

--drop database if exists sustc;
--CREATE database sustc WITH ENCODING = 'UTF8' LC_COLLATE = 'C' template =template0;



drop table if exists users;
create table users(
    mid bigint primary key ,
    name varchar,
    sex varchar,
    birthday varchar,
    level int,
    coin int,
    sign varchar,
    identity varchar,
    password varchar,
    qq varchar,
    wechat varchar
);
create index users_i1 on users(qq);
create index users_i2 on users(wechat);


drop table if exists videos;
create table videos(
    bv varchar primary key ,
    title varchar,
    ownerMid bigint,
    ownerName varchar,
    commitTime timestamp,
    reviewTime timestamp,
    publicTime timestamp,
    duration float(10),
    description varchar,
    reviewer bigint
);
create index videos_i1 on videos(ownerMid);




drop table if exists followings;
create table followings(
    mid1 bigint,
    mid2 bigint,
    primary key (mid1,mid2)
);
create index followings_i1 on followings(mid1);
create index followings_i2 on followings(mid2);

drop table if exists videolikes;
create table videolikes(
    bv varchar,
    mid bigint,
    primary key (bv,mid)
);
create index videolikes_i1 on videolikes(bv);
create index videolikes_i2 on videolikes(mid);

drop table if exists coins;
create table coins(
    bv varchar,
    mid bigint,
    primary key (bv,mid)
);
create index coins_i1 on coins(bv);
create index coins_i2 on coins(mid);


drop table if exists favorites;
create table favorites(
    bv varchar,
    mid bigint,
    primary key (bv,mid)
);
create index favorites_i1 on favorites(bv);
create index favorites_i2 on favorites(mid);

drop table if exists viewers;
create table viewers(
    bv varchar,
    mid bigint,
    viewTime float(10),
    primary key (bv,mid)
);
create index viewers_i1 on viewers(bv);
create index viewers_i2 on viewers(mid);
create index viewers_i3 on viewers(viewtime);


drop table if exists danmus;
create table danmus(
    id bigint primary key ,
    bv varchar,
    mid bigint,
    time float(10),
    content varchar,
    postTime timestamp
);


drop table if exists danmulikes;
create table danmulikes(
    id bigint ,
    mid bigint,
    primary key (id,mid)
);
create index danmulikes_i1 on danmulikes(id);
create index danmulikes_i2 on danmulikes(mid);


--必须要有
create or replace function qmin(x numeric,y numeric) returns numeric as
$$
    begin
    if x<=y then return  x;
        else  return y;
       end if;
    end
$$ language plpgsql;


--
--
--
--
--
-- select max(mid) from users;
--
--
--
--
--
--
--
-- drop table if exists tmp;
-- create table tmp(
--     id serial  ,
--     name varchar
-- );
--
-- begin;
-- lock table tmp in ACCESS EXCLUSIVE mode;
-- commit;