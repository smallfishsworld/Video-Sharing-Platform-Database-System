-- Audit log for the users table: every inserted, updated or deleted row, and every TRUNCATE, is
-- recorded together with the database role and the transaction time.
--
-- The log keeps no credentials. The 2023 version of this file (commit 2e811bd, a row-level
-- trigger) copied password, qq and wechat into every log row; here an AuthInfo with only a qq or
-- wechat value authenticates without a password, so those two are credentials as well, and all
-- three are left out. Logs written by the 2023 version still hold them and must be purged
-- before any real deployment.
--
-- It is a change log, not a tamper-proof audit trail:
--   * an UPDATE logs only the new row, not the old one;
--   * stamp is now(), the start of the transaction, and no key orders the entries;
--   * db_user is the role that ran the statement, and the services connect as the schema owner,
--     so it does not name the application user;
--   * the function runs with the rights of that role, so every role that writes users needs
--     INSERT on log_users and can append entries of its own (see usercontrol.sql).
--
-- The triggers are statement-level and read the affected rows from transition tables, so a
-- statement that touches many rows (for example the bulk import's COPY) costs one
-- INSERT ... SELECT instead of one trigger invocation per row. TRUNCATE has no transition table;
-- it is logged as one row without user data, so emptying the table (the bulk importer does this
-- when it starts and when it cleans up after a failure) stays visible in the trail.
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
    identity  varchar
);

create function audit_users() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'TRUNCATE' then
        insert into log_users (operation, stamp, db_user) values (tg_op, now(), current_user);
    elsif tg_op = 'DELETE' then
        insert into log_users
        select tg_op, now(), current_user, o.mid, o.name, o.sex, o.birthday, o.level, o.coin, o.sign, o.identity
        from old_rows o;
    else
        insert into log_users
        select tg_op, now(), current_user, n.mid, n.name, n.sex, n.birthday, n.level, n.coin, n.sign, n.identity
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
create trigger users_audit_truncate
    after truncate on users
    for each statement execute function audit_users();

-- To turn auditing off again:
--   drop trigger users_audit_insert on users;
--   drop trigger users_audit_update on users;
--   drop trigger users_audit_delete on users;
--   drop trigger users_audit_truncate on users;
