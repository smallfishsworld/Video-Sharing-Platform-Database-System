-- This version's recommendFriends query (RecommenderServiceImpl.FRIENDS), page 1 of size 10.
select c.mid
from (select o.follower_mid as mid, count(*) as common
      from followings mine
      join followings o on o.followee_mid = mine.followee_mid
      where mine.follower_mid = :mid
      group by o.follower_mid) c
join users u on u.mid = c.mid
where c.mid <> :mid
  and not exists (select 1 from followings x where x.follower_mid = :mid and x.followee_mid = c.mid)
order by c.common desc, u.level desc, c.mid
limit 10 offset 0
