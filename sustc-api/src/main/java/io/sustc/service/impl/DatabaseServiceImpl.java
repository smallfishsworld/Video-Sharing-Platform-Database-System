package io.sustc.service.impl;

import io.sustc.dto.*;
import io.sustc.service.DanmuService;
import io.sustc.service.DatabaseService;
import io.sustc.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * It's important to mark your implementation class with {@link Service} annotation.
 * As long as the class is annotated and implements the corresponding interface, you can place it under any package.
 */


class MyRun1 implements Runnable{

    DataSource dataSource;
    List<UserRecord> userRecords;
    int[] chk=new int[100];
    MyRun1(DataSource ds,List<UserRecord> list,int[] ch)
    {
        dataSource=ds;
        userRecords=list;
        chk=ch;
    }
    public void run()
    {
        int i,j;
        try {
            String sql;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;

            sql="insert into users values(?,?,?,?,?,?,?,?,?,?,?);";
            stmt = conn.prepareStatement(sql);
            for(i=0;i< userRecords.size();i++)
            {
//sql="insert into users values("+now.getMid()+",'"+chgs(now.getName())+"','"+chgs(now.getSex())+"','"+chgs(now.getBirthday())+"',"+now.getLevel()+","+now.getCoin()+",'"+chgs(now.getSign())+"','"+chgs(now.getIdentity().toString())+"','"+chgs(now.getPassword())+"','"+chgs(now.getQq())+"','"+chgs(now.getWechat())+"');";
                //System.out.println("check"+sql);
                UserRecord now=userRecords.get(i);
                stmt.setLong(1,now.getMid());
                stmt.setString(2,now.getName());
                stmt.setString(3,now.getSex());
                stmt.setString(4,now.getBirthday());
                stmt.setInt(5,now.getLevel());
                stmt.setInt(6,now.getCoin());
                stmt.setString(7,now.getSign());
                stmt.setString(8,now.getIdentity().toString());
                stmt.setString(9,now.getPassword());
                stmt.setString(10,now.getQq());
                stmt.setString(11,now.getWechat());
                stmt.addBatch();

            }
            stmt.executeBatch();
            stmt.clearBatch();
            conn.close();
            chk[1]=1;
        }
        catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}


class MyRun2 implements Runnable{

    DataSource dataSource;
    List<UserRecord> userRecords;
    int[] chk=new int[100];
    MyRun2(DataSource ds,List<UserRecord> list,int[] ch)
    {
        dataSource=ds;
        userRecords=list;
        chk=ch;
    }
    public void run()
    {
        int i,j;
        try {
            String sql;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;
            sql="insert into followings values(?,?);";
            stmt = conn.prepareStatement(sql);
            for(i=0;i< userRecords.size();i++)
            {
                UserRecord now=userRecords.get(i);
                for(j=0;j<now.getFollowing().length;j++)
                {
                    stmt.setLong(1,now.getMid());
                    stmt.setLong(2,now.getFollowing()[j]);
                    stmt.addBatch();
                }
            }
            stmt.executeBatch();
            stmt.clearBatch();
            conn.close();
            chk[2]=1;
        }
        catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}


class MyRun3 implements Runnable{

    DataSource dataSource;
    List<VideoRecord> videoRecords;
    int[] chk=new int[100];
    MyRun3(DataSource ds,List<VideoRecord>  list,int[] ch)
    {
        dataSource=ds;
        videoRecords=list;
        chk=ch;
    }
    public void run()
    {
        int i,j;
        try {
            String sql;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;


            sql="insert into videolikes values(?,?);";
            stmt = conn.prepareStatement(sql);
            for(i=0;i< videoRecords.size();i++) {
                VideoRecord now=videoRecords.get(i);
                for(j=0;j<now.getLike().length;j++)
                {
                    stmt.setString(1,now.getBv());
                    stmt.setLong(2,now.getLike()[j]);
                    stmt.addBatch();
                }
            }
            stmt.executeBatch();
            stmt.clearBatch();


            sql="insert into coins values(?,?);";
            stmt = conn.prepareStatement(sql);
            for(i=0;i< videoRecords.size();i++) {
                VideoRecord now=videoRecords.get(i);
                for(j=0;j<now.getCoin().length;j++)
                {
                    stmt.setString(1,now.getBv());
                    stmt.setLong(2,now.getCoin()[j]);
                    stmt.addBatch();
                }
            }
            stmt.executeBatch();
            stmt.clearBatch();


            conn.close();
            chk[3]=1;
        }
        catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}


class MyRun4 implements Runnable{

    DataSource dataSource;
    List<DanmuRecord> danmuRecords;
    int[] chk=new int[100];
    MyRun4(DataSource ds,List<DanmuRecord>   list,int[] ch)
    {
        dataSource=ds;
        danmuRecords=list;
        chk=ch;
    }
    public void run()
    {
        int i,j;
        try {
            String sql;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;

            sql="insert into danmus values(?,?,?,?,?,?);";
            stmt = conn.prepareStatement(sql);
            for(i=0;i<danmuRecords.size();i++)
            {
                DanmuRecord now=danmuRecords.get(i);
                stmt.setLong(1,i+1);
                stmt.setString(2,now.getBv());
                stmt.setLong(3,now.getMid());
                stmt.setFloat(4,now.getTime());
                stmt.setString(5,now.getContent());
                stmt.setTimestamp(6,now.getPostTime());
                stmt.addBatch();
            }
            stmt.executeBatch();
            stmt.clearBatch();


            sql="insert into danmulikes values(?,?);";
            stmt = conn.prepareStatement(sql);
            for(i=0;i<danmuRecords.size();i++)
            {
                DanmuRecord now=danmuRecords.get(i);
                for(j=0;j<now.getLikedBy().length;j++)
                {
                    stmt.setLong(1,i+1);
                    stmt.setLong(2,now.getLikedBy()[j]);
                    stmt.addBatch();
                }
            }


            stmt.executeBatch();
            stmt.clearBatch();
            conn.close();
            chk[4]=1;
        }
        catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}

@Service
@Slf4j
public class DatabaseServiceImpl implements DatabaseService {

    /**
     * Getting a {@link DataSource} instance from the framework, whose connections are managed by HikariCP.
     * <p>
     * Marking a field with {@link Autowired} annotation enables our framework to automatically
     * provide you a well-configured instance of {@link DataSource}.
     * Learn more: <a href="https://www.baeldung.com/spring-dependency-injection">Dependency Injection</a>
     */
    @Autowired
    private DataSource dataSource;

    /*
    // 处理字符串的单引号问题
    public String chgs(String s){
        String t="";
        int n,i;
        n=s.length();
        for(i=0;i<n;i++)
        {
            if(s.charAt(i)=='\'')
            {
                ////System.out.println("check OK");
                t=t+"''";
            }
            else t=t+s.charAt(i);
        }
        return t;
    }
    */

    @Override
    public List<Integer> getGroupMembers() {
        //TODO: replace this with your own student IDs in your group
        return Arrays.asList(12345678);
    }



    @Override
    public void importData(
            List<DanmuRecord> danmuRecords,
            List<UserRecord> userRecords,
            List<VideoRecord> videoRecords
    ) {



        // TODO: implement your import logic
        ////System.out.println("!!!check OK!!!!");
        // insert userRecords


        int i,j;
        try {

            String sql;
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt;
            int[] chk=new int[100];
            for(i=0;i<50;i++) chk[i]=0;
            Thread t1 = new Thread(new MyRun1(dataSource,userRecords,chk));
            t1.start();

            Thread t2 = new Thread(new MyRun2(dataSource,userRecords,chk));
            t2.start();

            Thread t3= new Thread(new MyRun3(dataSource,videoRecords,chk));
            t3.start();

            Thread t4= new Thread(new MyRun4(dataSource, danmuRecords,chk));
            t4.start();

//
//            Thread t2=new Thread(new MyRun2(dataSource,userRecords));
//            t2.start();

//            sql="insert into users values(?,?,?,?,?,?,?,?,?,?,?);";
//            stmt = conn.prepareStatement(sql);
//            for(i=0;i< userRecords.size();i++)
//            {
////sql="insert into users values("+now.getMid()+",'"+chgs(now.getName())+"','"+chgs(now.getSex())+"','"+chgs(now.getBirthday())+"',"+now.getLevel()+","+now.getCoin()+",'"+chgs(now.getSign())+"','"+chgs(now.getIdentity().toString())+"','"+chgs(now.getPassword())+"','"+chgs(now.getQq())+"','"+chgs(now.getWechat())+"');";
//                //System.out.println("check"+sql);
//
//                UserRecord now=userRecords.get(i);
//                stmt.setLong(1,now.getMid());
//                stmt.setString(2,now.getName());
//                stmt.setString(3,now.getSex());
//                stmt.setString(4,now.getBirthday());
//                stmt.setInt(5,now.getLevel());
//                stmt.setInt(6,now.getCoin());
//                stmt.setString(7,now.getSign());
//                stmt.setString(8,now.getIdentity().toString());
//                stmt.setString(9,now.getPassword());
//                stmt.setString(10,now.getQq());
//                stmt.setString(11,now.getWechat());
//                stmt.addBatch();
//
//            }
//            stmt.executeBatch();
//            stmt.clearBatch();


//            sql="insert into followings values(?,?);";
//            stmt = conn.prepareStatement(sql);
//            for(i=0;i< userRecords.size();i++)
//            {
//                UserRecord now=userRecords.get(i);
//                for(j=0;j<now.getFollowing().length;j++)
//                {
//                    stmt.setLong(1,now.getMid());
//                    stmt.setLong(2,now.getFollowing()[j]);
//                    stmt.addBatch();
//                }
//            }
//            stmt.executeBatch();
//            stmt.clearBatch();



            sql="insert into videos values(?,?,?,?,?,?,?,?,?,?);";
            stmt = conn.prepareStatement(sql);
            for(i=0;i< videoRecords.size();i++)
            {
                ////System.out.println("check id "+i);
                VideoRecord now=videoRecords.get(i);
                stmt.setString(1,now.getBv());
                stmt.setString(2,now.getTitle());
                stmt.setLong(3,now.getOwnerMid());
                stmt.setString(4,now.getOwnerName());
                stmt.setTimestamp(5,now.getCommitTime());
                stmt.setTimestamp(6,now.getReviewTime());
                stmt.setTimestamp(7,now.getPublicTime());
                stmt.setFloat(8,now.getDuration());
                stmt.setString(9,now.getDescription());
                stmt.setLong(10,now.getReviewer());
                stmt.addBatch();

            }
            stmt.executeBatch();
            stmt.clearBatch();







            sql="insert into favorites values(?,?);";
            stmt = conn.prepareStatement(sql);
            for(i=0;i< videoRecords.size();i++) {
                VideoRecord now=videoRecords.get(i);
                for(j=0;j<now.getFavorite().length;j++)
                {
                    stmt.setString(1,now.getBv());
                    stmt.setLong(2,now.getFavorite()[j]);
                    stmt.addBatch();
                }
            }
            stmt.executeBatch();
            stmt.clearBatch();




            sql="insert into viewers values(?,?,?);";
            stmt = conn.prepareStatement(sql);
            for(i=0;i< videoRecords.size();i++) {
                VideoRecord now=videoRecords.get(i);
                for(j=0;j<now.getViewerMids().length;j++)
                {
                    stmt.setString(1,now.getBv());
                    stmt.setLong(2,now.getViewerMids()[j]);
                    stmt.setFloat(3,now.getViewTime()[j]);
                    stmt.addBatch();
                }
            }
            stmt.executeBatch();
            stmt.clearBatch();




            while(chk[1]==0||chk[2]==0||chk[3]==0||chk[4]==0)
            {
                Thread.sleep(1);
            }

        }
        catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        //System.out.println(danmuRecords.size());
        //System.out.println(userRecords.size());
        //ystem.out.println(videoRecords.size());


    }

    /*
     * The following code is just a quick example of using jdbc datasource.
     * Practically, the code interacts with database is usually written in a DAO layer.
     *
     * Reference: [Data Access Object pattern](https://www.baeldung.com/java-dao-pattern)
     */

    @Override
    public void truncate() {
        // You can use the default truncate script provided by us in most cases,
        // but if it doesn't work properly, you may need to modify it.

        String sql = "DO $$\n" +
                "DECLARE\n" +
                "    tables CURSOR FOR\n" +
                "        SELECT tablename\n" +
                "        FROM pg_tables\n" +
                "        WHERE schemaname = 'public';\n" +
                "BEGIN\n" +
                "    FOR t IN tables\n" +
                "    LOOP\n" +
                "        EXECUTE 'TRUNCATE TABLE ' || QUOTE_IDENT(t.tablename) || ' CASCADE;';\n" +
                "    END LOOP;\n" +
                "END $$;\n";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql))
        {
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Integer sum(int a, int b) {
        String sql = "SELECT ?+?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, a);
            stmt.setInt(2, b);
            log.info("SQL: {}", stmt);

            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }




}
