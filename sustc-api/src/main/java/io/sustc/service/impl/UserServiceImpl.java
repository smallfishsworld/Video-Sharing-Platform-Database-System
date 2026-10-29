package io.sustc.service.impl;

import io.sustc.dto.AuthInfo;
import io.sustc.dto.RegisterUserReq;
import io.sustc.dto.UserInfoResp;
import io.sustc.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Random;

@Service
@Slf4j
public class UserServiceImpl implements UserService {
    @Autowired
    private DataSource dataSource;
    static boolean chkBirthday(String s)
    {
        if(s==null) return true;
        int n,idx1,idx2;
        n=s.length();
        idx1=s.indexOf("月");
        idx2=s.indexOf("日");
        if(idx1==-1||idx2==-1||idx1>idx2||idx2!=n-1||s.charAt(0)>'9'||s.charAt(0)<'0') return false;
        int num1,num2;
        int i,now;
        char c;
        num1=-1;num2=-1;
        now=0;
        for(i=0;i<n;i++)
        {
            c=s.charAt(i);
            if(c<'0'||c>'9')
            {
                if(now!=0)
                {
                    if(num1==-1) num1=now;
                    else num2=now;

                    now=0;
                }
            }
            else
            {
                now=now*10+c-'0';
            }
        }

        //System.out.println("string "+s+"   num1 num2   "+num1+"  "+num2);
        int tnu=30;
        if(num1<1||num1>12) return false;
        switch(num1){
            case 1,3,5,7,8,10,12:
                tnu=31;break;
            default:tnu=30;
        }
        if(num2<1||num2>tnu) return false;
        //System.out.println("check OK");
        return true;
    }



    //register 123 123 123 123 MALE 1月1日 123
    //user register 'f7c44a8cb3cd4aeb' '097904460803598801' '7a6466ee#f044#' 'aaaafc1b1' 'MALE'  '5月31日'  'f8a03713-5f87-4c)'
    @Override
    public long register(RegisterUserReq req)
    {
        //System.out.println("chk point 1");
        if(AuthHelper.isNullOrEmpty(req.getPassword())||AuthHelper.isNullOrEmpty(req.getName())||AuthHelper.isNullOrEmpty(req.getSex().toString()))
        {
            return -1;
        }
        //System.out.println("chk point 2");
        if(chkBirthday(req.getBirthday())==false)
        {
            return -1;
        }
        //System.out.println("chk point 3");
        try {
            String sql;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;
            ResultSet rst;
            sql="select * from users where qq=? or wechat=?";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,req.getQq());
            stmt.setString(2,req.getWechat());
            rst=stmt.executeQuery();
            if(rst.next())
            {
                conn.close();
                stmt.close();
                return -1;
            }
            sql="insert into users values(?,?,?,?,?,?,?,?,?,?,?) returning mid;";
            stmt = conn.prepareStatement(sql);

            Random r=new Random();
            long inf=(long)1e18;
            stmt.setLong(1,Math.abs(r.nextLong()%inf+1));
            stmt.setString(2,req.getName());
            stmt.setString(3,req.getSex().toString());
            stmt.setString(4,req.getBirthday());
            stmt.setInt(5,0);
            stmt.setInt(6,0);
            stmt.setString(7,req.getSign());
            stmt.setString(8,"USER");
            stmt.setString(9,req.getPassword());
            stmt.setString(10,req.getQq());
            stmt.setString(11,req.getWechat());
            stmt.execute();
            rst=stmt.getResultSet();
            rst.next();
            long newmid=rst.getLong(1);
            conn.close();stmt.close();
            return newmid;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    //user delete 1966193 '' '' ''  1704073
    public boolean deleteAccount(AuthInfo auth, long mid)
    {
        try {
            String sql;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;
            ResultSet rst;
            //System.out.println("check point 1  "+auth.toString()+"  "+mid);
            long mid2=AuthHelper.resolveMid(auth,conn);
            //System.out.println("check point 2    mid2   "+mid2);
            if(mid2==-1)
            {
                conn.close();
                return false;
            }
            sql="select * from users where mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            rst=stmt.executeQuery();
            if(!rst.next())
            {
                conn.close();stmt.close();
                return false;
            }
            String identity2=AuthHelper.getIdentity(mid2,conn);
            String identity=AuthHelper.getIdentity(mid,conn);
            //.println("chk identity"+identity2);

            if(identity2.equals("USER")) {
                if (mid2 != mid) {
                    conn.close();
                    stmt.close();
                    return false;
                }
            }
            else{
                if(identity.equals("SUPERUSER")&&mid2!=mid)
                {
                    conn.close();
                    stmt.close();
                    return false;
                }
            }

            sql="delete from coins where mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            stmt.execute();

            //System.out.println("check point 3");

            sql="delete from danmulikes where mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            stmt.execute();

            sql="delete from danmus where mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            stmt.execute();

            sql="delete from favorites where mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            stmt.execute();

            //System.out.println("check point 4");
            sql="delete from followings where mid1=? or mid2=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            stmt.setLong(2,mid);
            stmt.execute();

            //System.out.println("check point 5");

            sql="delete from users where mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            stmt.execute();

            //System.out.println("check point 6");
            sql="delete from videolikes where mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            stmt.execute();

            //System.out.println("check point 7");
            sql="delete from viewers where mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            stmt.execute();

            //System.out.println("check point 8");
            sql="delete from videos where ownermid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            stmt.execute();

            conn.close();stmt.close();
            return true;
        } catch (SQLException e) {
        throw new RuntimeException(e);
        }

    }


    @Override
    //follow 233333 '' '' 'If@Eoavxco*@'  1525136
    public boolean follow(AuthInfo auth, long followeeMid)
    {


        try{
            String sql;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;
            ResultSet rst;


            Long mid=AuthHelper.resolveMid(auth,conn);
            //System.out.println("check mid "+ mid);
            if(mid==-1) {
                conn.close();
                return false;
            }


            sql="select * from users where mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,followeeMid);
            rst=stmt.executeQuery();
            if(!rst.next())
            {
                conn.close();stmt.close();
                return false;
            }

            if(mid==followeeMid)
            {
                conn.close();stmt.close();
                return false;
            }

            boolean type;// type=0 : 加      type=1:删
            sql="select * from followings where mid1=? and mid2=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            stmt.setLong(2,followeeMid);
            rst=stmt.executeQuery();
            if(rst.next())
            {
                type=true;
            }
            else
            {
                type=false;
            }

            if(type)
            {
                sql="delete from followings where mid1=? and mid2=?";
                stmt = conn.prepareStatement(sql);
                stmt.setLong(1,mid);
                stmt.setLong(2,followeeMid);
                stmt.execute();
            }
            else
            {
                sql="insert into followings values(?,?)";
                stmt = conn.prepareStatement(sql);
                stmt.setLong(1,mid);
                stmt.setLong(2,followeeMid);
                stmt.execute();
            }
            conn.close();stmt.close();

        }catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return true;

    }


    @Override
    //get-user-info 12557
    public UserInfoResp getUserInfo(long mid)
    {
        //System.out.println("check getUserInfo" + mid);
        UserInfoResp now=new UserInfoResp();

        try
        {
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;
            String sql;
            ResultSet rst;
            //System.out.println("N chk point 0");


            sql="select * from users where mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            rst=stmt.executeQuery();
            if(!rst.next())
            {
                conn.close();
                stmt.close();
                return null;
            }


            now.setMid(mid);
            now.setCoin(rst.getInt("coin"));

           // System.out.println("N chk point 1");

            int cnt;
            long [] following;
            sql="select count(*) from followings where mid1=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            rst=stmt.executeQuery();
            rst.next();
            cnt=rst.getInt(1);
            following=new long[cnt];
            sql="select mid2 from followings where mid1=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            rst=stmt.executeQuery();
            cnt=0;
            while(rst.next())
            {
                following[cnt]=rst.getLong(1);
                cnt++;
            }
            now.setFollowing(following);

            //System.out.println("N chk point 2");

            long [] follower;
            sql="select count(*) from followings where mid2=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            rst=stmt.executeQuery();
            rst.next();
            cnt=rst.getInt(1);
            follower=new long[cnt];
            sql="select mid1 from followings where mid2=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            rst=stmt.executeQuery();
            cnt=0;
            while(rst.next())
            {
                follower[cnt]=rst.getLong(1);
                cnt++;
            }
            now.setFollower(follower);

            //System.out.println("N chk point 3");

            String [] watched;
            sql="select count(*) from viewers where mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            rst=stmt.executeQuery();
            rst.next();
            cnt=rst.getInt(1);
            watched=new String[cnt];
            sql="select bv from viewers where mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            rst=stmt.executeQuery();
            cnt=0;
            while(rst.next())
            {
                watched[cnt]=rst.getString(1);
                cnt++;
            }
            now.setWatched(watched);

            //System.out.println("N chk point 4");

            String [] liked;
            sql="select count(*) from videolikes where mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            rst=stmt.executeQuery();
            rst.next();
            cnt=rst.getInt(1);
            liked=new String[cnt];
            sql="select bv from videolikes where mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            rst=stmt.executeQuery();
            cnt=0;
            while(rst.next())
            {
                liked[cnt]=rst.getString(1);
                cnt++;
            }
            now.setLiked(liked);

            //System.out.println("N chk point 5");

            String [] collected;
            sql="select count(*) from favorites where mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            rst=stmt.executeQuery();
            rst.next();
            cnt=rst.getInt(1);
            collected=new String[cnt];
            sql="select bv from favorites where mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            rst=stmt.executeQuery();
            cnt=0;
            while(rst.next())
            {
                collected[cnt]=rst.getString(1);
                cnt++;
            }
            now.setCollected(collected);


            //System.out.println("N chk point 6");

            String [] posted;
            sql="select count(*) from videos where ownermid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            rst=stmt.executeQuery();
            rst.next();
            cnt=rst.getInt(1);
            posted=new String[cnt];
            sql="select bv from videos where ownermid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,mid);
            rst=stmt.executeQuery();
            cnt=0;
            while(rst.next())
            {
                posted[cnt]=rst.getString(1);
                cnt++;
            }
            now.setPosted(posted);

            //System.out.println("N chk point 7");
            conn.close();
            stmt.close();

        }catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return now;
    }
}
