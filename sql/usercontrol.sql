-- Example roles for sharing the database with other applications. Optional: the services, the
-- benchmark and the tests connect as the owner of the schema and need none of these roles.
-- UserControlScriptTest loads this file and checks the rights of every role.
--
-- Run after build.sql and audit.sql, as the owner of the schema, with CREATEROLE. Passwords are
-- not kept in this file; pass them as psql variables:
--
--   psql -v super_manager_password=... -v manager_password=... -v normal_user_password=... \
--        -f sql/usercontrol.sql
--
--   supermanager  every right on every table, including the audit log.
--   manager       reads and writes the nine application tables, as the services do; the derived
--                 tables are read-only, and the audit log can only be appended to (see below).
--   normaluser    reads the application data except the credentials (password, qq, wechat),
--                 checks credentials through authenticate_user(), and writes nothing.
--
-- UNSAFE AS AN END-USER SETUP. Two gaps remain that this file cannot close without changing
-- functions that the services and the benchmark run:
--   * The audit trigger (audit.sql) runs with the rights of the role whose statement fires it and
--     records that role, so a role that writes users needs INSERT on log_users: manager can
--     append forged entries (it cannot read, change or delete entries).
--   * No role is limited to the rows of one user. manager acts for every user, as the services
--     do. A role for end users that writes would need row-level security keyed on the signed-in
--     user, which this file does not provide; normaluser therefore writes nothing. It reads every
--     row of the tables it can read, including videos that are not yet published or reviewed,
--     which the services hide from regular users.
-- Passwords are stored as given (the course data has plain-text passwords).

create role supermanager login password :'super_manager_password';
create role manager login password :'manager_password';
create role normaluser login password :'normal_user_password';

-- The derived-table triggers run as the owner of their functions, so writing an interaction
-- needs no write right on video_stats or video_coviews. Only these attributes change; the
-- functions' code is that of build.sql. The fixed search_path keeps objects of the calling role
-- from being used in place of the tables.
alter function derived_videos() security definer set search_path = public, pg_temp;
alter function derived_interactions() security definer set search_path = public, pg_temp;
alter function derived_viewers() security definer set search_path = public, pg_temp;

-- Checks credentials by the rules of the services (io.sustc.service.impl.auth.Authenticator):
-- if qq and wechat are both given they must belong to the same user; otherwise mid and password
-- are tried first, then qq, then wechat; empty strings count as absent. Returns the user and
-- whether it is a superuser, or no row. It runs as the owner, so callers need no right on the
-- credential columns.
create function authenticate_user(p_mid bigint, p_password varchar, p_qq varchar, p_wechat varchar)
    returns table (user_mid bigint, is_superuser boolean)
    language plpgsql stable security definer
    set search_path = public, pg_temp as
$$
declare
    v_password varchar := nullif(p_password, '');
    v_qq       varchar := nullif(p_qq, '');
    v_wechat   varchar := nullif(p_wechat, '');
begin
    if v_qq is not null and v_wechat is not null then
        return query select u.mid, u.identity = 'SUPERUSER' from users u where u.qq = v_qq and u.wechat = v_wechat;
        return;
    end if;
    if v_password is not null then
        return query select u.mid, u.identity = 'SUPERUSER' from users u where u.mid = p_mid and u.password = v_password;
        if found then
            return;
        end if;
    end if;
    if v_qq is not null then
        return query select u.mid, u.identity = 'SUPERUSER' from users u where u.qq = v_qq;
    elsif v_wechat is not null then
        return query select u.mid, u.identity = 'SUPERUSER' from users u where u.wechat = v_wechat;
    end if;
end
$$;
revoke all on function authenticate_user(bigint, varchar, varchar, varchar) from public;

-- supermanager: every right, including the audit log.
grant all on all tables in schema public to supermanager;
grant all on all sequences in schema public to supermanager;
grant execute on function authenticate_user(bigint, varchar, varchar, varchar) to supermanager;

-- manager: the rights of the services. Writing users fires the audit trigger, which appends to
-- log_users as manager: the INSERT right below is the first gap described above.
grant select, insert, update, delete
    on users, videos, followings, viewers, videolikes, coins, favorites, danmus, danmulikes
    to manager;
grant select on video_stats, video_coviews to manager;
grant usage on all sequences in schema public to manager;
grant insert on log_users to manager;
grant execute on function authenticate_user(bigint, varchar, varchar, varchar) to manager;

-- normaluser: reads without the credential columns.
grant select (mid, name, sex, birthday, level, coin, sign, identity) on users to normaluser;
grant select
    on videos, followings, viewers, videolikes, coins, favorites, danmus, danmulikes,
       video_stats, video_coviews
    to normaluser;
grant execute on function authenticate_user(bigint, varchar, varchar, varchar) to normaluser;
