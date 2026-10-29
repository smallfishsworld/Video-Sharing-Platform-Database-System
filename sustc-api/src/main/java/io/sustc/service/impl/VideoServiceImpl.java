package io.sustc.service.impl;


import io.sustc.dto.AuthInfo;
import io.sustc.dto.PostVideoReq;
import io.sustc.service.VideoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
@Slf4j
public class VideoServiceImpl implements VideoService {

    @Autowired
    private DataSource dataSource;



    boolean chkCanNotSearchOrIsOwner(Long mid,String identity,String bv,Connection conn)
    {
       // System.out.println("mcheck  chkCanNotSearchOrIsOwner!");
        try {
            String sql;
            PreparedStatement stmt;
            ResultSet rst;
            Timestamp nowtime=Timestamp.from(Instant.now());
            sql="select * from videos where bv=?";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,bv);
            rst=stmt.executeQuery();
            if(!rst.next())
            {
                stmt.close();
                return true;
            }
            if(identity.equals("USER")&&nowtime.before(rst.getTimestamp("publictime")))
            {
                stmt.close();
                return true;
            }
            if(mid==rst.getLong("ownermid"))
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



    public String generateNewBv()
    {
        //System.out.println("mcheck  generateNewBv!");
        //System.out.println("generateNewBv check");
        Random rand = new Random();
        String s="";
        char c;
        int i,num;
        s="BV1";
        for(i=1;i<=9;i++)
        {
            //System.out.println("generate string at "+i);
            num=rand.nextInt(62);
            if(num>=0&&num<10)
            {
                c=(char)('0'+num);
            }
            else if(num>=10&&num<36)
            {
                c=(char)('a'+num-10);
            }
            else
            {
                c=(char)('A'+num-36);
            }
            s=s+c;
        }
        return s;
    }

    boolean chkVideoReq(Long mid,Timestamp nowtime,PostVideoReq req)
    {

        if(AuthHelper.isNullOrEmpty(req.getTitle())||req.getDuration()<10||req.getPublicTime()==null||req.getPublicTime().before(nowtime))
        {
            return false;
        }

        return true;
    }
    @Override
    //post-video 1966089 '' '' ''   'Shenzhen Vlog'   'no description'  32 '2024-03-17 18:13:13'
    public String postVideo(AuthInfo auth, PostVideoReq req) {
        //System.out.println(" postVideo   check point 1");
        long mid;
        String bv,ownername;
        Timestamp nowtime=Timestamp.from(Instant.now());
        //LocalDateTime ldt=LocalDateTime.now();
        //long timestamp = ldt.toInstant(ZoneOffset.of("+8")).toEpochMilli();



        try {
            String sql;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;
            ResultSet rst;


            mid=AuthHelper.resolveMid(auth,conn);
            if(mid==-1)
            {
                conn.close();
                return null;
            }


            if(!chkVideoReq(mid,nowtime,req))
            {
                conn.close();
                return null;
            }



            sql="select * from videos where title=? and ownermid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,req.getTitle());
            stmt.setLong(2,mid);
            rst=stmt.executeQuery();
            if(rst.next())
            {
                conn.close();stmt.close();
                return null;
            }




            //System.out.println(" postVideo   check point 4");

            bv=generateNewBv();
            sql="select * from users where mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            rst=stmt.executeQuery();
            rst.next();
            ownername=rst.getString("name");

            //System.out.println(" postVideo   check point 5");

            sql="insert into videos values(?,?,?,?,?,?,?,?,?,?);";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,bv);
            stmt.setString(2,req.getTitle());
            stmt.setLong(3,mid);
            stmt.setString(4,ownername);
            stmt.setTimestamp(5,nowtime);
            stmt.setTimestamp(6,null);
            stmt.setTimestamp(7,req.getPublicTime());
            stmt.setFloat(8,req.getDuration());
            stmt.setString(9,req.getDescription());
            stmt.setLong(10,0);
            stmt.execute();


            //System.out.println(" postVideo   check point 6");
            conn.close();stmt.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return bv;
    }

    @Override
    //video delete 250858633 '' '' ''  'BV1bt4y1x7Wk'
    //18202105
    public boolean deleteVideo(AuthInfo auth, String bv) {
        try {
            String sql;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;
            ResultSet rst;
            long mid=AuthHelper.resolveMid(auth,conn);
            if(mid==-1)
            {
                conn.close();
                return false;
            }
            String identity=AuthHelper.getIdentity(mid,conn);

            sql="select * from videos where bv=? ";
            if(identity.equals("USER"))
            {
                sql=sql+" and ownermid=?";
            }
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,bv);
            if(identity.equals("USER"))
            {
                stmt.setLong(2,mid);
            }

           // System.out.println("check identity "+identity);
           // System.out.println("check sql"+stmt);
            rst=stmt.executeQuery();
            if(!rst.next())
            {
                conn.close();stmt.close();
                return false;
            }

            sql="delete from coins where bv=?";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,bv);
            stmt.execute();

            sql="delete from danmus where bv=?";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,bv);
            stmt.execute();


            sql="delete from favorites where bv=?";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,bv);
            stmt.execute();

            sql="delete from videolikes where bv=?";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,bv);
            stmt.execute();

            sql="delete from viewers where bv=?";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,bv);
            stmt.execute();

            sql="delete from videos where bv=?";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,bv);
            stmt.execute();
            conn.close();stmt.close();
            return true;

        } catch (SQLException e) {
        throw new RuntimeException(e);
        }
    }

    @Override
    //update-video-info  250858633 '' '' ''   'BV1cJ411H77j'  'Test1219' 'No' 479 '2025-01-14 18:00:24.000000'
    public boolean updateVideoInfo(AuthInfo auth, String bv, PostVideoReq req) {
        if (req == null) {
            return false;
        }
        Timestamp nowtime = Timestamp.from(Instant.now());

        try (Connection conn = dataSource.getConnection()) {
            long mid = AuthHelper.resolveMid(auth, conn);
            if (mid == AuthHelper.INVALID_MID) {
                return false;
            }
            if (!chkVideoReq(mid, nowtime, req)) {
                return false;
            }

            boolean needsReReview;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "select title, description, duration, publicTime, reviewTime from videos where bv = ? and ownerMid = ?")) {
                stmt.setString(1, bv);
                stmt.setLong(2, mid);
                try (ResultSet rs = stmt.executeQuery()) {
                    // Video not found, or the current user is not its owner.
                    if (!rs.next()) {
                        return false;
                    }
                    // Duration must not change.
                    if (rs.getFloat("duration") != req.getDuration()) {
                        return false;
                    }
                    // Nothing changed.
                    if (Objects.equals(rs.getString("title"), req.getTitle())
                            && Objects.equals(rs.getString("description"), req.getDescription())
                            && Objects.equals(rs.getTimestamp("publicTime"), req.getPublicTime())) {
                        return false;
                    }
                    Timestamp reviewTime = rs.getTimestamp("reviewTime");
                    needsReReview = reviewTime != null && reviewTime.before(nowtime);
                }
            }

            // The new title must not clash with another video of the same owner.
            try (PreparedStatement stmt = conn.prepareStatement(
                    "select 1 from videos where ownerMid = ? and title = ? and bv <> ?")) {
                stmt.setLong(1, mid);
                stmt.setString(2, req.getTitle());
                stmt.setString(3, bv);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return false;
                    }
                }
            }

            String sql = needsReReview
                    ? "update videos set title = ?, description = ?, publicTime = ?, reviewTime = null, reviewer = 0 where bv = ?"
                    : "update videos set title = ?, description = ?, publicTime = ? where bv = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, req.getTitle());
                stmt.setString(2, req.getDescription());
                stmt.setTimestamp(3, req.getPublicTime());
                stmt.setString(4, bv);
                stmt.executeUpdate();
            }
            return needsReReview;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }



    public String prechgString(String s)
    {
        String t="",h;
        int n,i;
        char c;
        n=s.length();
        for(i=0;i<n;i++)
        {
            c=s.charAt(i);
            if(c=='+'||c=='%'||c=='?'||c=='.')
            {
                t=t+"\\"+c;
            }
            else
            {
                if(c>='A'&&c<='Z')
                {
                    c=(char)('a'+(c-'A'));
                }
                t=t+c;
            }
        }
        return t;
    }

    //video search 1704272 '' '' ''  '我 一'  100 1
    //video search 1966089 '' '' ''  'Shen'  100 1

    //video search 1704272 '' '' ''  '?'  100 1
    //video search 0 '' '' 'htMh!EWVPruq'  'you m TUbE mM' 10 1
    @Override
    public List<String> searchVideo(AuthInfo auth, String keywords, int pageSize, int pageNum) {


        //System.out.println("keywords 我 一");
        //System.out.println("check point 0!!!");
        //System.out.println(auth.toString()+"    "+keywords+"  "+pageSize+" "+pageNum);
        Timestamp nowtime=Timestamp.from(Instant.now());
        try {
            String sql,identity;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;
            ResultSet rst;
            int i,j,bh=0;
            String[] lis;
            Long mid;


            mid=AuthHelper.resolveMid(auth,conn);


            if(mid==-1||AuthHelper.isNullOrEmpty(keywords)||pageSize<=0||pageNum<=0)
            {
                conn.close();
                return null;
            }

            identity=AuthHelper.getIdentity(mid,conn);




            lis=keywords.split(" ");


            for(i=0;i<lis.length;i++)
            {
                lis[i]=prechgString(lis[i]);
                //System.out.println("chk lis "+i+"  "+lis[i]);
            }



            sql="with tmp as ( select distinct t.bv,t.cnt,count(*) as views from (select bv, ";


//            for(i=0;i<lis.length;i++)
//            {
//                if(i!=0) sql=sql+"+";
//                sql=sql+"(case when title like ? then 1 else 0 end)+(case when description like ? then 1 else 0 end)+(case when ownername like ? then 1 else 0 end)";
//            }

            for(i=0;i<lis.length;i++)
            {
                if(i!=0) sql=sql+"+";
                sql=sql+"(array_length(regexp_split_to_array(lower(title),?),1)-1)";

                sql=sql+"+(array_length(regexp_split_to_array(lower(description),?),1)-1)";

                sql=sql+"+(array_length(regexp_split_to_array(lower(ownername),?),1)-1)";
            }

//            sql=sql+" as cnt from videos where (";
//
//
//            for(i=0;i<lis.length;i++)
//            {
//                if(i!=0) sql=sql+" or ";
//                sql=sql+" title like '%'|| ? ||'%' or description like '%'|| ? ||'%' or ownername like '%'|| ? ||'%' ";
//            }
//
//            sql=sql+")";
//
            sql=sql+" as cnt from videos ";
//            for(i=0;i<lis.length;i++)
//            {
//                if(i!=0) sql=sql+" or ";
//                sql=sql+" title like '%'|| ? ||'%' or description like '%'|| ? ||'%' or ownername like '%'|| ? ||'%' ";
//            }
//            sql=sql+")";


            if(identity.equals("USER"))
            {

                sql=sql+" where (publictime < ? or ownermid = ? )";
            }



            sql=sql+" order by bv) t left join viewers r on t.bv=r.bv where t.cnt>0 group by t.bv,t.cnt)    select * from tmp order by cnt desc,views desc offset ? limit ?";




            //System.out.println("check point 1: "+sql);

            stmt = conn.prepareStatement(sql);


            bh=0;
            for(i=0;i<lis.length;i++)
            {
                for(j=0;j<3;j++)
                {
                    bh++;
                    stmt.setString(bh,lis[i]);
                }
            }

//
//            for(i=0;i<lis.length;i++)
//            {
//                for(j=0;j<3;j++)
//                {
//                    bh++;
//                    stmt.setString(bh,lis[i]);
//                }
//            }

            if(identity.equals("USER"))
            {
                bh++;
                stmt.setTimestamp(bh,nowtime);
                bh++;
                stmt.setLong(bh,mid);
            }





            bh++;
            stmt.setInt(bh,pageSize*(pageNum-1));
            bh++;
            stmt.setInt(bh,pageSize);

            //System.out.println("check sql sentence:  "+stmt);
            rst=stmt.executeQuery();

            List<String> list=new ArrayList<String>();
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
    //video viewrate 'BV1wd4y1G7Rd'
    public double getAverageViewRate(String bv)
    {
        try {
            String sql,identity;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;
            ResultSet rst;
            double viewtime,duration;
            sql="select sum(viewtime) as tottime,count(*) as tot from viewers where bv=?";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,bv);
            rst=stmt.executeQuery();
            rst.next();
            if(rst.getInt("tot")==0)
            {
                conn.close();stmt.close();
                return -1;
            }
            viewtime=rst.getDouble("tottime")/rst.getDouble("tot");
            sql="select duration from videos where bv=?";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,bv);
            rst=stmt.executeQuery();
            if(!rst.next())
            {
                conn.close();stmt.close();
                return -1;
            }
            duration=rst.getDouble("duration");
            conn.close();stmt.close();
            return viewtime/duration;
        }
        catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    //video hotspot 'BV14r4y1i7vZ'
    //video hotspot 'BV187411H782'
    public Set<Integer> getHotspot(String bv) {
        try {
            String sql, identity;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;
            ResultSet rst;
            Set<Integer> st=new HashSet<Integer>();
            sql="select time from danmus where bv=? order by time ";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,bv);
            rst=stmt.executeQuery();
            //System.out.println("check sql: "+stmt);

            int i,cnt=0,now,las=-1,vmx=0;
            double tim;
            //System.out.println("check point 1");
            while(rst.next())
            {
                tim=rst.getDouble("time");
                now=(int)Math.floor(tim/10.0);
                //System.out.println("time:"+tim+" now:"+now);
                if(now==las)
                {
                    cnt++;
                }
                else
                {
                    vmx=Math.max(vmx,cnt);
                    las=now;
                    cnt=1;
                }
            }
            vmx=Math.max(vmx,cnt);
            if(las==-1)
            {
                conn.close();stmt.close();
                return st;
            }
            //System.out.println("check point 2");

            rst=stmt.executeQuery();
            cnt=0;
            while(rst.next())
            {
                tim=rst.getDouble("time");
                now=(int)Math.floor(tim/10.0);

                if(now==las)
                {
                    cnt++;
                }
                else
                {
                    if(cnt==vmx) st.add(las);
                    las=now;
                    cnt=1;
                }
            }
            if(cnt==vmx) st.add(las);
           // System.out.println("check vmx:"+vmx);
            conn.close();stmt.close();
            return st;

        }
        catch (SQLException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    //video review 1966193 '' '' ''  'BV1ki0F1Wnyn'
    public boolean reviewVideo(AuthInfo auth, String bv) {
        try (Connection conn = dataSource.getConnection()) {
            long mid = AuthHelper.resolveMid(auth, conn);
            if (mid == AuthHelper.INVALID_MID || !AuthHelper.isSuperuser(mid, conn)) {
                return false;
            }
            // Only an unreviewed video owned by someone else can be reviewed.
            // Doing the checks in the UPDATE itself keeps concurrent reviews from both succeeding.
            try (PreparedStatement stmt = conn.prepareStatement(
                    "update videos set reviewTime = ?, reviewer = ? where bv = ? and reviewTime is null and ownerMid <> ?")) {
                stmt.setTimestamp(1, Timestamp.from(Instant.now()));
                stmt.setLong(2, mid);
                stmt.setString(3, bv);
                stmt.setLong(4, mid);
                return stmt.executeUpdate() == 1;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

    }



    @Override
    //video coin 2138 '' '' ''  'BV1ki0F1Wnyn'
    public boolean coinVideo(AuthInfo auth, String bv) {
        try {
            String sql;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;
            ResultSet rst;
            Long mid=AuthHelper.resolveMid(auth,conn);
            if(mid==-1)
            {
                conn.close();
                return false;
            }
            String identity=AuthHelper.getIdentity(mid,conn);
            if(chkCanNotSearchOrIsOwner(mid,identity,bv,conn))
            {
                conn.close();
                return false;
            }
            //System.out.println("check point 0");

            sql="select coin from users where mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            rst=stmt.executeQuery();
            rst.next();
            if(rst.getInt("coin")<=0)
            {
                conn.close();stmt.close();
                return false;
            }
            int nowcoin=rst.getInt("coin");


            //System.out.println("check point 1");

            sql="select * from coins where bv=? and mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,bv);
            stmt.setLong(2,mid);
            rst=stmt.executeQuery();
            if(rst.next())
            {
                conn.close();stmt.close();
                return false;
            }

            //System.out.println("check point 2");



            sql="insert into coins values(?,?)";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,bv);
            stmt.setLong(2,mid);
            stmt.execute();

            //System.out.println("check point 3");

            sql="update users set coin=? where mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1,nowcoin-1);
            stmt.setLong(2,mid);
            stmt.execute();
            conn.close();stmt.close();
            return true;
        }
        catch (SQLException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    //video like 2138 '' '' ''  'BV1ki0F1Wnyn'
    public boolean likeVideo(AuthInfo auth, String bv) {
        try {
            String sql;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;
            ResultSet rst;
            long mid=AuthHelper.resolveMid(auth,conn);
            if(mid==-1)
            {
                conn.close();
                return false;
            }
            String identity=AuthHelper.getIdentity(mid,conn);
            if(chkCanNotSearchOrIsOwner(mid,identity,bv,conn))
            {
                conn.close();
                return false;
            }

            sql="select * from videolikes where bv=? and mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,bv);
            stmt.setLong(2,mid);
            rst=stmt.executeQuery();

            boolean havliked=false;
            if(rst.next()) havliked=true;

            if(havliked)
            {
                sql="delete from videolikes where bv=? and mid=?";
                stmt = conn.prepareStatement(sql);
                stmt.setString(1,bv);
                stmt.setLong(2,mid);
                stmt.execute();
                conn.close();stmt.close();
                return false;
            }
            else {
                sql="insert into videolikes values(?,?)";
                stmt = conn.prepareStatement(sql);
                stmt.setString(1,bv);
                stmt.setLong(2,mid);
                stmt.execute();
                conn.close();stmt.close();
                return true;
            }

        }
        catch (SQLException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    //video collect 2138 '' '' ''  'BV1ki0F1Wnyn'
    public boolean collectVideo(AuthInfo auth, String bv) {
        try {
            String sql;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;
            ResultSet rst;
            long mid=AuthHelper.resolveMid(auth,conn);
            if(mid==-1)
            {
                conn.close();
                return false;
            }
            String identity=AuthHelper.getIdentity(mid,conn);
            if(chkCanNotSearchOrIsOwner(mid,identity,bv,conn))
            {
                conn.close();
                return false;
            }

            sql="select * from favorites where bv=? and mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,bv);
            stmt.setLong(2,mid);
            rst=stmt.executeQuery();

            boolean havcollected=false;
            if(rst.next()) havcollected=true;

            if(havcollected)
            {
                sql="delete from favorites where bv=? and mid=?";
                stmt = conn.prepareStatement(sql);
                stmt.setString(1,bv);
                stmt.setLong(2,mid);
                stmt.execute();
                conn.close();stmt.close();
                return false;
            }
            else {
                sql="insert into favorites values(?,?)";
                stmt = conn.prepareStatement(sql);
                stmt.setString(1,bv);
                stmt.setLong(2,mid);
                stmt.execute();
                conn.close();stmt.close();
                return true;
            }

        }
        catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
