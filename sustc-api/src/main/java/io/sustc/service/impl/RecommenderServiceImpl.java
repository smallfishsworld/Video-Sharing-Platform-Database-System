package io.sustc.service.impl;

import io.sustc.dto.AuthInfo;
import io.sustc.service.RecommenderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class RecommenderServiceImpl implements RecommenderService {
    @Autowired
    private DataSource dataSource;
    boolean chkCanNotFindVideo(String bv,Connection conn)
    {
        try {
            String sql;
            PreparedStatement stmt;
            ResultSet rst;
            sql="select * from videos where bv=?";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,bv);
            rst=stmt.executeQuery();
            if(!rst.next())
            {
                stmt.close();
                return true;
            }
            stmt.close();
            return false;
        }
        catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
    @Override
    //rec video 'BV1XP4y1T7zz'
    public List<String> recommendNextVideo(String bv) {
        try {
            String sql;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;
            ResultSet rst;
            if(chkCanNotFindVideo(bv,conn))
            {
                conn.close();
                return null;
            }
            List<String> list=new ArrayList<String>();
            sql="select a.bv,count(b.mid) as cnt from viewers a inner join viewers b on a.mid=b.mid where b.bv=? and a.bv !=? group by a.bv order by cnt desc limit 5";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,bv);
            stmt.setString(2,bv);
            rst=stmt.executeQuery();
            while(rst.next())
            {
                list.add(rst.getString("bv"));
            }
            conn.close();stmt.close();
            return list;
        }
        catch (SQLException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    //rec general 100 1
    public List<String> generalRecommendations(int pageSize, int pageNum) {
        try {
            String sql;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;
            ResultSet rst;
            //System.out.println("check point 1");
            if(pageSize<=0||pageNum<=0)
            {
                conn.close();
                return null;
            }
            List<String> list=new ArrayList<String>();



//            sql="create or replace function qmin(x numeric,y numeric) returns numeric as\n" +
//                    "$$\n" +
//                    "    begin\n" +
//                    "    if x<=y then return  x;\n" +
//                    "        else  return y;\n" +
//                    "       end if;\n" +
//                    "    end\n" +
//                    "$$ language plpgsql;";
//            stmt = conn.prepareStatement(sql);
//            stmt.execute();

            //System.out.println("check point 2");

            sql="select\n" +
                    "        a.bv,\n" +
                    "        case views when 0 then 0\n" +
                    "        else\n" +
                    "        qmin(cast(b.lik as numeric)/cast(a.views as numeric),1.0)+\n" +
                    "        qmin(cast(c.coi as numeric)/cast(a.views as numeric),1.0)+\n" +
                    "        qmin(cast(d.fav as numeric)/cast(a.views as numeric),1.0)+\n" +
                    "        qmin(cast(e.dan as numeric)/cast(a.views as numeric),1.0)+\n" +
                    "        qmin(cast(a.avgviewtime/a.duration as numeric),1.0)\n" +
                    "        end as score\n" +
                    "from\n" +
                    "(select v.bv,v.duration ,count(r.mid) as views,sum(viewtime)/count(*) as avgviewtime from videos v left join viewers r on v.bv=r.bv group by v.bv,v.duration) a\n" +
                    "inner join (select v.bv,count(r.mid) as lik from videos v left join videolikes r on v.bv=r.bv group by v.bv) b on a.bv=b.bv\n" +
                    "inner join (select v.bv,count(r.mid) as coi from videos v left join coins r on v.bv=r.bv group by v.bv) c on a.bv=c.bv\n" +
                    "inner join (select v.bv,count(r.mid) as fav from videos v left join favorites r on v.bv=r.bv group by v.bv)d on a.bv=d.bv\n" +
                    "inner join (select v.bv,count(r.mid) as dan from videos v left join danmus r on v.bv=r.bv group by v.bv)e on a.bv=e.bv order by score desc ";
            sql=sql+" offset ? limit ?";
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1,pageSize*(pageNum-1));
            stmt.setInt(2,pageSize);
            //System.out.println("check point 3");
            rst=stmt.executeQuery();
            while(rst.next()){
                list.add(rst.getString("bv"));
            }
            //System.out.println("check point !");
            conn.close();stmt.close();
            return list;
        }
        catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    //rec user 682265 '' '' '' 100 1
    public List<String> recommendVideosForUser(AuthInfo auth, int pageSize, int pageNum) {
        try{
            String sql;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;
            ResultSet rst;
            List<String> list=new ArrayList<String>();
            long mid=AuthHelper.resolveMid(auth,conn);
            if(mid==-1)
            {
                conn.close();
                return null;
            }
            if(pageSize<=0||pageNum<=0)
            {
                conn.close();
                return null;
            }


//            sql="select v.bv,t.cnt,u.level,u.mid,u.name,v.publictime from videos v inner join\n" +
//                    "(select r.bv,count(f.mid2) as cnt from\n" +
//                    "(select a.mid2 from followings a inner join  followings b on a.mid1=b.mid2 and a.mid2=b.mid1 where a.mid1= ? ) f\n" +
//                    "inner join viewers r\n" +
//                    "    on f.mid2=r.mid group by r.bv) t\n" +
//                    "on v.bv=t.bv\n" +
//                    "inner join users u on v.ownermid=u.mid\n" +
//                    "                  order by t.cnt desc,u.level desc,v.publictime desc";


            sql="select v.bv,t.cnt,u.level,u.mid,u.name,v.publictime,vie.viewtime,vie.mid as chkmid from videos v inner join\n" +
                    "(select r.bv,count(f.mid2) as cnt from\n" +
                    "(select a.mid2 from followings a inner join  followings b on a.mid1=b.mid2 and a.mid2=b.mid1 where a.mid1=?) f\n" +
                    "inner join viewers r\n" +
                    "    on f.mid2=r.mid group by r.bv) t\n" +
                    "on v.bv=t.bv\n" +
                    "inner join users u on v.ownermid=u.mid\n" +
                    "left join viewers vie on vie.mid=? and v.bv=vie.bv\n" +
                    "                  order by t.cnt desc,u.level desc,v.publictime desc;";


            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            stmt.setLong(2,mid);
            rst=stmt.executeQuery();
            int cnt=0;
            while(rst.next())
            {
                //System.out.println("chkmid "+rst.getLong("chkmid"));
                if(rst.getLong("chkmid")==0)
                {
                    cnt++;
                    if(cnt>pageSize*(pageNum-1)&&cnt<=pageSize*pageNum)
                    {
                        list.add(rst.getString("bv"));
                    }
                }

            }

            if(cnt==0)
            {
                conn.close();stmt.close();
                return generalRecommendations(pageSize,pageNum);
            }
            conn.close();stmt.close();
            return list;

        }catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    //rec friends 614824 '' '' '' 100 1000
    public List<Long> recommendFriends(AuthInfo auth, int pageSize, int pageNum) {
        try{
            String sql;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;
            ResultSet rst;
            long mid=AuthHelper.resolveMid(auth,conn);
            if(mid==-1)
            {
                conn.close();
                return null;
            }
            if(pageSize<=0||pageNum<=0)
            {
                conn.close();
                return null;
            }

            sql="select s.mid1,s.cnt,u.level from\n" +
                    "(select t.mid1,h.mid2 as chkmid,t.cnt from\n" +
                    "(select b.mid1,count(*) as cnt from followings a\n" +
                    "inner join followings b on a.mid2=b.mid2 where a.mid1=? and b.mid1!=? group by b.mid1) t\n" +
                    "    left join followings h on h.mid1=? and h.mid2=t.mid1) s\n" +
                    "inner join  users u on s.mid1=u.mid where s.chkmid is null order by s.cnt desc,u.level desc,s.mid1 ";
            sql=sql+" offset ? limit ?;";

//            sql="select s.mid1,s.cnt,u.level from\n" +
//                    "(select t.mid1,h.mid2 as chkmid,t.cnt from\n" +
//                    "(select b.mid1,count(*) as cnt from followings a\n" +
//                    "inner join followings b on a.mid2=b.mid2 where a.mid1=? and b.mid1!=? group by b.mid1) t\n" +
//                    "    left join followings h on h.mid1=? and h.mid2=t.mid1) s\n" +
//                    "inner join  users u on s.mid1=u.mid where s.chkmid is null order by s.cnt desc,u.level desc ";
//            sql=sql+" offset ? limit ?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            stmt.setLong(2,mid);
            stmt.setLong(3,mid);
            stmt.setInt(4,pageSize*(pageNum-1));
            stmt.setInt(5,pageSize);
            rst=stmt.executeQuery();
            List<Long> list=new ArrayList<Long>();
            while(rst.next())
            {
                list.add(rst.getLong("mid1"));
            }
            conn.close();stmt.close();
            return list;

        }catch (SQLException e) {
            throw new RuntimeException(e);
        }

    }
}
