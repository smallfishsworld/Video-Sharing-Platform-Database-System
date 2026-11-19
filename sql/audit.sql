-- Audit log for the users table: every inserted, updated or deleted row is recorded together with
-- the database role and the transaction time.
--
-- The triggers are statement-level and read the affected rows from transition tables, so a
-- statement that touches many rows (for example the bulk import's COPY) costs one
-- INSERT ... SELECT instead of one trigger invocation per row.
create table log_users (
    operation varchar   not null,
    stamp     timestamp not null,
    db_user   varchar   not null,
    mid       bigint,
    name      varchar,
    sex       varchar,
    birthday  varchar,
    level     smallint,
    coin      integer,
    sign      varchar,
    identity  varchar,
    password  varchar,
    qq        varchar,
    wechat    varchar
);

create function audit_users() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        insert into log_users
        select tg_op, now(), current_user,
               o.mid, o.name, o.sex, o.birthday, o.level, o.coin, o.sign, o.identity, o.password, o.qq, o.wechat
        from old_rows o;
    else
        insert into log_users
        select tg_op, now(), current_user,
               n.mid, n.name, n.sex, n.birthday, n.level, n.coin, n.sign, n.identity, n.password, n.qq, n.wechat
        from new_rows n;
    end if;
    return null;
end
$$;

create trigger users_audit_insert
    after insert on users referencing new table as new_rows
    for each statement execute function audit_users();
create trigger users_audit_update
    after update on users referencing new table as new_rows
    for each statement execute function audit_users();
create trigger users_audit_delete
    after delete on users referencing old table as old_rows
    for each statement execute function audit_users();

-- To turn auditing off again:
--   drop trigger users_audit_insert on users;
--   drop trigger users_audit_update on users;
--   drop trigger users_audit_delete on users;
