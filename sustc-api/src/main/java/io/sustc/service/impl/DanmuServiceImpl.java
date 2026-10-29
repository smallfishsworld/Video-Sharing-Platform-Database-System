package io.sustc.service.impl;

import io.sustc.dto.AuthInfo;
import io.sustc.service.DanmuService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

@Service
@Slf4j
public class DanmuServiceImpl implements DanmuService {
    @Autowired
    private DataSource dataSource;



    //此方法与videoserviceimpl中的代码复用
    boolean chkCanNotSearchOrIsOwnerorTioutbound(Long mid,String identity,String bv,float time,Connection conn)
    {
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
            if(time>rst.getFloat("duration"))
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

    boolean chkCanNotFindVideoOrVideoIsNotPublished(String bv,Connection conn)
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
            Timestamp nowtime=Timestamp.from(Instant.now());
            if(nowtime.before(rst.getTimestamp("publictime")))
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


    public boolean chkWatched(Long mid,String bv,Connection conn)
    {
        try {
            String sql;
            PreparedStatement stmt;
            ResultSet rst;
            sql="select * from viewers where bv=? and mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,bv);
            stmt.setLong(2,mid);
            rst=stmt.executeQuery();
            if(rst.next())
            {
                stmt.close();
                return true;

            }
            else
            {
                stmt.close();
                return false;
            }
        }catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    //danmu send 560001 '' '' ''  'BV14r4y1i7vZ' 'test1220 danmu' 1.5
    //danmu send 1783696 '' '' ''  'BV1ki0F1Wnyn'  'hello' 2.6
    public long sendDanmu(AuthInfo auth, String bv, String content, float time) {
        try {
            //System.out.println("chk sendDanmu");
            String sql;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;
            ResultSet rst;
            Timestamp nowtime=Timestamp.from(Instant.now());
            long mid=AuthHelper.resolveMid(auth,conn);



            if(mid==-1)
            {
                conn.close();
                return -1;
            }

            if(AuthHelper.isNullOrEmpty(content))
            {
                conn.close();
                return -1;
            }

            String identity=AuthHelper.getIdentity(mid,conn);
            if(chkCanNotSearchOrIsOwnerorTioutbound(mid,identity,bv,time,conn))
            {
                conn.close();
                return -1;
            }
            if(!chkWatched(mid,bv,conn))
            {
                conn.close();
                return -1;
            }


            //System.out.println("danmu check point 2  newid "+newid);
            sql="insert into danmus values(?,?,?,?,?,?) returning id;";
            stmt = conn.prepareStatement(sql);
            Random r=new Random();
            long inf=(long)1e18;
            stmt.setLong(1,Math.abs(r.nextLong()%inf+1));
            stmt.setString(2,bv);
            stmt.setLong(3,mid);
            stmt.setDouble(4,time);
            stmt.setString(5,content);
            stmt.setTimestamp(6,nowtime);
            stmt.execute();
            rst=stmt.getResultSet();
            rst.next();
            long newid=rst.getLong(1);


            //System.out.println("check point 3");
            conn.close();stmt.close();
            return newid;



        }catch (SQLException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    //danmu display 'BV1mG4y1z761'  0 1000 false
    public List<Long> displayDanmu(String bv, float timeStart, float timeEnd, boolean filter) {
        try {
            String sql;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;
            ResultSet rst;
            List<Long> list=new ArrayList<Long>();

            if(chkCanNotFindVideoOrVideoIsNotPublished(bv,conn))
            {
                conn.close();
                return null;
            }
            if(timeStart<0||timeEnd<0||timeStart>timeEnd)
            {
                conn.close();
                return null;
            }
            sql="select duration from videos where bv=?";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,bv);
            rst=stmt.executeQuery();
            rst.next();
            if(timeEnd>rst.getFloat("duration"))
            {
                conn.close();
                return null;
            }


            sql="select * from danmus where bv=? and time>=? and time<=? order by time";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1,bv);
            stmt.setFloat(2,timeStart);
            stmt.setFloat(3,timeEnd);
            rst=stmt.executeQuery();
            if(filter==false)
            {
                while(rst.next())
                {
                    list.add(rst.getLong("id"));
                }
            }
            else {
                String las="",now;
                HashMap< String,Integer> mp=new HashMap<String,Integer>();
                while(rst.next())
                {
                    now=rst.getString("content");
                    if(mp.containsKey(now)) continue;
                    else
                    {
                        list.add(rst.getLong("id"));
                        mp.put(now,1);
                    }
                }
            }
            conn.close();stmt.close();
            return list;

        }catch (SQLException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    //danmu like 1783696 '' '' '' 308
    public boolean likeDanmu(AuthInfo auth, long id) {
        try {
            //System.out.println("mchk like danmu "+auth.toString()+"  "+id);
            String sql;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;
            ResultSet rst;
            long mid=AuthHelper.resolveMid(auth,conn);



            String bv;

            if(mid==-1)
            {
                conn.close();
                return false;
            }
            sql="select * from danmus where id=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,id);
            rst=stmt.executeQuery();
            if(!rst.next())
            {
                conn.close();stmt.close();
                return false;
            }


            bv=rst.getString("bv");
            if(!chkWatched(mid,bv,conn))
            {
                conn.close();stmt.close();
                return false;
            }


            sql="select * from danmulikes where id=? and mid=?";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1,id);
            stmt.setLong(2,mid);
            rst=stmt.executeQuery();
            boolean liked;
            if(rst.next()) liked=true;
            else liked=false;

            if(liked==false)
            {
                sql="insert into danmulikes values(?,?)";
                stmt = conn.prepareStatement(sql);
                stmt.setLong(1,id);
                stmt.setLong(2,mid);
                stmt.execute();
                conn.close();stmt.close();
                return true;
            }
            else {
                sql="delete from danmulikes where id=? and mid=?";
                stmt = conn.prepareStatement(sql);
                stmt.setLong(1,id);
                stmt.setLong(2,mid);
                stmt.execute();
                conn.close();stmt.close();
                return false;
            }


        }catch (SQLException e) {
            throw new RuntimeException(e);
        }

    }
}
