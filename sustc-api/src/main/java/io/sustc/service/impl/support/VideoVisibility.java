package io.sustc.service.impl.support;

import io.sustc.service.impl.auth.CurrentUser;
import java.sql.Timestamp;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * The one visibility rule shared by search and all interactions (like, coin, collect).
 *
 * <p>A video is <em>published</em> once it has been reviewed and its public time (if any) has
 * passed. Unpublished videos are visible only to superusers and to their owner.
 */
public final class VideoVisibility {

    /** SQL predicate on alias {@code v}; true when the video is published at {@code :now}. */
    public static final String PUBLISHED =
            "(v.review_time is not null and (v.public_time is null or v.public_time <= :now))";

    /** SQL predicate on alias {@code v}; needs the parameters bound by {@link #bind}. */
    public static final String VISIBLE_TO_ME = "(:superuser or v.owner_mid = :me or " + PUBLISHED + ")";

    private VideoVisibility() {}

    /** Binds {@code :me}, {@code :superuser} and {@code :now}. */
    public static MapSqlParameterSource bind(CurrentUser user, Timestamp now) {
        return new MapSqlParameterSource()
                .addValue("me", user.mid())
                .addValue("superuser", user.superuser())
                .addValue("now", now);
    }
}
