
drop table if exists log_users;
create table log_users(
    operation varchar,
    stamp timestamp,
    userid varchar,
    mid bigint,
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


create or replace function audit() returns trigger
as
$$
    BEGIN
        if(tg_op='DELETE' ) then
        insert into log_users select tg_op,now(),user,old.mid,old.name,old.sex,old.birthday,old.level,old.coin,old.sign,old.identity,old.password,old.qq,old.wechat;
        elsif( tg_op='INSERT') then
        insert into log_users select tg_op,now(),user,new.mid,new.name,new.sex,new.birthday,new.level,new.coin,new.sign,new.identity,new.password,new.qq,new.wechat;
        elsif(tg_op='UPDATE') then
        insert into log_users select tg_op,now(),user,new.mid,new.name,new.sex,new.birthday,new.level,new.coin,new.sign,new.identity,new.password,new.qq,new.wechat;
        end if;
        return null;
end;
$$ language plpgsql;


CREATE or replace TRIGGER check_after
AFTER INSERT OR UPDATE OR DELETE ON users
FOR EACH ROW
EXECUTE FUNCTION audit();

-- To turn auditing off again:
-- drop trigger check_after on users;
