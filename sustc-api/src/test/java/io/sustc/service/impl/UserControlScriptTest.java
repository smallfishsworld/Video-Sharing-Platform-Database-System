package io.sustc.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Loads sql/usercontrol.sql and checks the rights of each of its roles, both as grants and by
 * running statements as the role. Everything happens in one transaction that is rolled back at
 * the end: CREATE ROLE, GRANT and ALTER FUNCTION are transactional, so no role or grant outlives
 * the test.
 */
class UserControlScriptTest extends IntegrationTest {

    private static final List<String> APPLICATION_TABLES = List.of(
            "users", "videos", "followings", "viewers", "videolikes", "coins", "favorites", "danmus", "danmulikes");
    private static final List<String> DERIVED_TABLES = List.of("video_stats", "video_coviews");
    private static final List<String> PRIVILEGES = List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE");
    private static final String AUTHENTICATE = "authenticate_user(bigint, varchar, varchar, varchar)";

    @Autowired
    private DataSource dataSource;

    @Test
    void eachRoleHasTheDocumentedRights() throws Exception {
        long owner = fx.user("owner");
        long viewer = fx.user("viewer");
        long admin = fx.superuser("admin");
        long oidc = fx.userWithOidc("oidc", "qq-1", "wx-1");
        String bv = fx.video(owner, "v");
        String other = fx.video(owner, "other");

        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(false);
            try {
                try (Statement st = con.createStatement()) {
                    st.execute(script());
                    st.execute("create role test_nobody");
                }
                checkGrants(con);
                checkNormalUser(con, owner, viewer, admin, oidc, bv);
                checkManager(con, owner, viewer, bv, other);
                checkSuperManager(con);
            } finally {
                con.rollback();
                con.setAutoCommit(true);
            }
        }
        assertThat(count("select count(*) from pg_roles where rolname in ('supermanager', 'manager', 'normaluser')"))
                .isZero();
    }

    /** The table, column and function rights, by role. */
    private void checkGrants(Connection con) throws SQLException {
        List<String> all = new ArrayList<>(APPLICATION_TABLES);
        all.addAll(DERIVED_TABLES);
        all.add("log_users");
        for (String table : all) {
            for (String privilege : PRIVILEGES) {
                boolean application = APPLICATION_TABLES.contains(table);
                boolean derived = DERIVED_TABLES.contains(table);
                assertThat(hasTablePrivilege(con, "supermanager", table, privilege))
                        .as("supermanager %s on %s", privilege, table)
                        .isTrue();
                boolean manager = application
                        ? !privilege.equals("TRUNCATE")
                        : derived ? privilege.equals("SELECT") : privilege.equals("INSERT");
                assertThat(hasTablePrivilege(con, "manager", table, privilege))
                        .as("manager %s on %s", privilege, table)
                        .isEqualTo(manager);
                boolean normal = privilege.equals("SELECT") && (derived || application && !table.equals("users"));
                assertThat(hasTablePrivilege(con, "normaluser", table, privilege))
                        .as("normaluser %s on %s", privilege, table)
                        .isEqualTo(normal);
            }
        }
        for (String column : List.of("mid", "name", "sex", "birthday", "level", "coin", "sign", "identity")) {
            assertThat(value(con, "select has_column_privilege('normaluser', 'users', ?, 'SELECT')", column))
                    .as("normaluser SELECT on users.%s", column)
                    .isEqualTo(true);
        }
        for (String column : List.of("password", "qq", "wechat")) {
            assertThat(value(con, "select has_column_privilege('normaluser', 'users', ?, 'SELECT')", column))
                    .as("normaluser SELECT on users.%s", column)
                    .isEqualTo(false);
        }
        for (String role : List.of("supermanager", "manager", "normaluser")) {
            assertThat(value(con, "select has_function_privilege(?, '" + AUTHENTICATE + "', 'EXECUTE')", role))
                    .as("%s EXECUTE on authenticate_user", role)
                    .isEqualTo(true);
        }
        assertThat(value(con, "select has_function_privilege('test_nobody', '" + AUTHENTICATE + "', 'EXECUTE')"))
                .as("other roles EXECUTE on authenticate_user")
                .isEqualTo(false);
    }

    private void checkNormalUser(Connection con, long owner, long viewer, long admin, long oidc, String bv)
            throws SQLException {
        asRole(con, "normaluser");
        assertThat(value(con, "select name from users where mid = ?", owner)).isEqualTo("owner");
        assertThat(value(con, "select count(*) from videos where bv = ?", bv)).isEqualTo(1L);
        for (String column : List.of("password", "qq", "wechat")) {
            assertDenied(con, "select " + column + " from users where mid = ?", owner);
        }
        assertDenied(con, "select * from users where mid = ?", owner);
        assertDenied(con, "insert into users (name, password) values ('x', 'x')");
        assertDenied(con, "update users set coin = 100 where mid = ?", viewer);
        assertDenied(con, "insert into videolikes (bv, mid) values (?, ?)", bv, viewer);
        assertDenied(con, "insert into followings (follower_mid, followee_mid) values (?, ?)", viewer, owner);
        assertDenied(con, "update video_stats set likes = 0");
        assertDenied(con, "insert into log_users (operation, stamp, db_user) values ('X', now(), 'x')");
        assertDenied(con, "select count(*) from log_users");

        String auth = "select user_mid, is_superuser from authenticate_user(?, ?, ?, ?)";
        assertThat(rows(con, auth, owner, "pw-owner", null, null)).containsExactly(owner + " false");
        assertThat(rows(con, auth, admin, "pw-admin", "", "")).containsExactly(admin + " true");
        assertThat(rows(con, auth, owner, "wrong", null, null)).isEmpty();
        assertThat(rows(con, auth, 0L, null, "qq-1", null)).containsExactly(oidc + " false");
        assertThat(rows(con, auth, 0L, null, null, "wx-1")).containsExactly(oidc + " false");
        assertThat(rows(con, auth, 0L, null, "qq-1", "wx-1")).containsExactly(oidc + " false");
        assertThat(rows(con, auth, 0L, null, "qq-1", "other")).isEmpty();
        // A wrong password does not hide a valid qq, as in the services.
        assertThat(rows(con, auth, owner, "wrong", "qq-1", null)).containsExactly(oidc + " false");
        resetRole(con);
    }

    /**
     * manager writes interactions and users without any write right on the derived tables (the
     * derived_* functions run as their owner) and cannot touch the derived tables or read the
     * audit log; it can append to the audit log, the gap that the script describes.
     */
    private void checkManager(Connection con, long owner, long viewer, String bv, String other) throws SQLException {
        asRole(con, "manager");
        update(con, "insert into videolikes (bv, mid) values (?, ?)", bv, viewer);
        update(con, "insert into viewers (bv, mid, view_time) values (?, ?, 1)", bv, viewer);
        update(con, "insert into viewers (bv, mid, view_time) values (?, ?, 1)", other, viewer);
        update(con, "update users set coin = coin + 1 where mid = ?", viewer);
        assertDenied(con, "update video_stats set likes = 0");
        assertDenied(con, "insert into video_coviews (bv, other_bv, viewers) values (?, ?, 1)", bv, other);
        assertDenied(con, "delete from video_stats");
        assertDenied(con, "select count(*) from log_users");
        assertDenied(con, "delete from log_users");
        assertDenied(con, "truncate users");
        update(con, "insert into log_users (operation, stamp, db_user) values ('FORGED', now(), 'someone')");
        resetRole(con);

        assertThat(value(con, "select likes from video_stats where bv = ?", bv)).isEqualTo(1);
        assertThat(value(con, "select viewers from video_coviews where bv = ? and other_bv = ?", bv, other))
                .isEqualTo(1);
        assertThat(value(con, "select count(*) from log_users where operation = 'UPDATE' and db_user = 'manager'"))
                .isEqualTo(1L);

        asRole(con, "manager");
        update(con, "delete from users where mid = ?", owner);
        resetRole(con);
        assertThat(value(con, "select count(*) from videos")).isEqualTo(0L);
        assertThat(value(con, "select count(*) from video_stats")).isEqualTo(0L);
        assertThat(value(con, "select count(*) from video_coviews")).isEqualTo(0L);
    }

    private void checkSuperManager(Connection con) throws SQLException {
        asRole(con, "supermanager");
        assertThat(value(con, "select count(*) from users where password is not null"))
                .isEqualTo(3L);
        update(con, "delete from log_users");
        resetRole(con);
    }

    // -------------------------------------------------------------------------------------------

    /** sql/usercontrol.sql with its psql variables (the passwords) filled in. */
    private static String script() {
        String script = sqlScript("usercontrol.sql")
                .replace(":'super_manager_password'", "'test-super'")
                .replace(":'manager_password'", "'test-manager'")
                .replace(":'normal_user_password'", "'test-normal'");
        assertThat(script).doesNotContainPattern(":'[a-z_]+'");
        return script;
    }

    private static boolean hasTablePrivilege(Connection con, String role, String table, String privilege)
            throws SQLException {
        return (Boolean) value(con, "select has_table_privilege(?, ?, ?)", role, table, privilege);
    }

    private static void asRole(Connection con, String role) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute("set local role " + role);
        }
    }

    private static void resetRole(Connection con) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute("reset role");
        }
    }

    /** Runs the statement under a savepoint and expects it to fail for lack of a right (42501). */
    private static void assertDenied(Connection con, String sql, Object... args) throws SQLException {
        Savepoint savepoint = con.setSavepoint();
        try (PreparedStatement ps = prepare(con, sql, args)) {
            ps.execute();
            fail("not denied: " + sql);
        } catch (SQLException e) {
            assertThat(e.getSQLState())
                    .as("SQLSTATE of %s (%s)", sql, e.getMessage())
                    .isEqualTo("42501");
        }
        con.rollback(savepoint);
    }

    private static int update(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = prepare(con, sql, args)) {
            return ps.executeUpdate();
        }
    }

    private static Object value(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = prepare(con, sql, args);
                ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).as("a row from %s", sql).isTrue();
            return rs.getObject(1);
        }
    }

    /** The rows of the query, each as its columns joined by spaces. */
    private static List<String> rows(Connection con, String sql, Object... args) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (PreparedStatement ps = prepare(con, sql, args);
                ResultSet rs = ps.executeQuery()) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    row.add(String.valueOf(rs.getObject(i)));
                }
                rows.add(String.join(" ", row));
            }
        }
        return rows;
    }

    private static PreparedStatement prepare(Connection con, String sql, Object... args) throws SQLException {
        PreparedStatement ps = con.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null) {
                ps.setNull(i + 1, java.sql.Types.VARCHAR);
            } else {
                ps.setObject(i + 1, args[i]);
            }
        }
        return ps;
    }
}
