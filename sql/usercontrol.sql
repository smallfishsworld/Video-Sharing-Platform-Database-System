-- Example roles for sharing the database with other applications. Optional: the services, the
-- benchmark and the tests connect as the owner of the schema and need none of these roles.
--
-- Run after build.sql and audit.sql, as a role with CREATEROLE. Passwords are not kept in this
-- file; pass them as psql variables:
--
--   psql -v super_manager_password=... -v manager_password=... -v normal_user_password=... \
--        -f sql/usercontrol.sql
--
-- Triggers run with the privileges of the role whose statement fires them, so every role that
-- changes users must be able to append to the audit log (but not read or rewrite it).

create role supermanager login password :'super_manager_password';
create role manager login password :'manager_password';
create role normaluser login password :'normal_user_password';

-- Full control over the data, including the audit log.
grant all on all tables in schema public to supermanager;
grant all on all sequences in schema public to supermanager;

-- Reads and writes all application data.
grant select, insert, update, delete
    on users, videos, followings, viewers, videolikes, coins, favorites, danmus, danmulikes
    to manager;
grant usage on all sequences in schema public to manager;
grant insert on log_users to manager;

-- What the services do for a signed-in user: read the application data, register, follow,
-- like/coin/collect videos (a coin also updates the donor's balance) and send/like danmus. The
-- toggles need DELETE because liking twice removes the like.
grant select
    on users, videos, followings, viewers, videolikes, coins, favorites, danmus, danmulikes
    to normaluser;
grant insert on users, coins, danmus to normaluser;
grant update (coin) on users to normaluser;
grant insert, delete on followings, videolikes, favorites, danmulikes to normaluser;
grant usage on all sequences in schema public to normaluser;
grant insert on log_users to normaluser;
