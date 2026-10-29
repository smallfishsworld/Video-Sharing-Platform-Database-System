package io.sustc.service.impl;

import io.sustc.dto.AuthInfo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Authentication and user-lookup helpers shared by all service implementations.
 */
final class AuthHelper {

    static final long INVALID_MID = -1;

    private AuthHelper() {
    }

    static boolean isNullOrEmpty(String s) {
        return s == null || s.isEmpty();
    }

    /**
     * Resolves the current user from {@link AuthInfo}, following the rules in
     * {@link io.sustc.service.UserService#deleteAccount(AuthInfo, long)}:
     * <ul>
     *   <li>if both {@code qq} and {@code wechat} are given, they must belong to the same user;</li>
     *   <li>otherwise try {@code mid} + {@code password}, then {@code qq}, then {@code wechat}.</li>
     * </ul>
     *
     * @return the user's {@code mid}, or {@link #INVALID_MID} if the auth is invalid
     */
    static long resolveMid(AuthInfo auth, Connection conn) throws SQLException {
        if (auth == null) {
            return INVALID_MID;
        }
        boolean hasQq = !isNullOrEmpty(auth.getQq());
        boolean hasWechat = !isNullOrEmpty(auth.getWechat());

        if (hasQq && hasWechat) {
            return queryMid(conn, "select mid from users where qq = ? and wechat = ?",
                    auth.getQq(), auth.getWechat());
        }
        if (!isNullOrEmpty(auth.getPassword())) {
            long mid = queryMid(conn, "select mid from users where mid = ? and password = ?",
                    auth.getMid(), auth.getPassword());
            if (mid != INVALID_MID) {
                return mid;
            }
        }
        if (hasQq) {
            return queryMid(conn, "select mid from users where qq = ?", auth.getQq());
        }
        if (hasWechat) {
            return queryMid(conn, "select mid from users where wechat = ?", auth.getWechat());
        }
        return INVALID_MID;
    }

    /**
     * @return the user's identity ({@code "USER"} or {@code "SUPERUSER"}), or {@code null} if the user does not exist
     */
    static String getIdentity(long mid, Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("select identity from users where mid = ?")) {
            stmt.setLong(1, mid);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    static boolean isSuperuser(long mid, Connection conn) throws SQLException {
        return "SUPERUSER".equals(getIdentity(mid, conn));
    }

    private static long queryMid(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : INVALID_MID;
            }
        }
    }
}
