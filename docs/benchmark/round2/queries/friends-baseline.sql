-- The original implementation's recommendFriends query (vs-baseline RecommenderServiceImpl.java:234-240),
-- with its four parameters bound to :mid and page 1 of size 10.
select s.mid1,s.cnt,u.level from
(select t.mid1,h.mid2 as chkmid,t.cnt from
(select b.mid1,count(*) as cnt from followings a
inner join followings b on a.mid2=b.mid2 where a.mid1=:mid and b.mid1!=:mid group by b.mid1) t
    left join followings h on h.mid1=:mid and h.mid2=t.mid1) s
inner join  users u on s.mid1=u.mid where s.chkmid is null order by s.cnt desc,u.level desc,s.mid1  offset 0 limit 10
