package io.sustc.service.impl.auth;

import io.sustc.dto.AuthInfo;
import io.sustc.dto.UserRecord;
import io.sustc.service.impl.support.Strings;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolves {@link AuthInfo} to a user, following the rules in {@link
 * io.sustc.service.UserService#deleteAccount(AuthInfo, long)}.
 *
 * <ul>
 *   <li>If both {@code qq} and {@code wechat} are given, they must belong to the same user.
 *   <li>Otherwise {@code mid} + {@code password} is tried first, then {@code qq}, then {@code
 *       wechat}. Empty strings count as absent.
 * </ul>
 *
 * Each lookup returns the identity together with the id, so callers need no second query to
 * check for superuser rights.
 */
@Component
public class Authenticator {

    private static final String SELECT_USER = "select mid, identity from users where ";

    private final JdbcTemplate jdbc;

    public Authenticator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CurrentUser> authenticate(AuthInfo auth) {
        if (auth == null) {
            return Optional.empty();
        }
        String qq = Strings.emptyToNull(auth.getQq());
        String wechat = Strings.emptyToNull(auth.getWechat());
        if (qq != null && wechat != null) {
            return find("qq = ? and wechat = ?", qq, wechat);
        }
        String password = Strings.emptyToNull(auth.getPassword());
        if (password != null) {
            Optional<CurrentUser> user = find("mid = ? and password = ?", auth.getMid(), password);
            if (user.isPresent()) {
                return user;
            }
        }
        if (qq != null) {
            return find("qq = ?", qq);
        }
        if (wechat != null) {
            return find("wechat = ?", wechat);
        }
        return Optional.empty();
    }

    private Optional<CurrentUser> find(String condition, Object... args) {
        return jdbc
                .query(
                        SELECT_USER + condition,
                        (rs, rowNum) -> new CurrentUser(
                                rs.getLong("mid"),
                                UserRecord.Identity.SUPERUSER.name().equals(rs.getString("identity"))),
                        args)
                .stream()
                .findFirst();
    }
}
