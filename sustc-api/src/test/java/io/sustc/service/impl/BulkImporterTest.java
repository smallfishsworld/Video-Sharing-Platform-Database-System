package io.sustc.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.sustc.dto.AuthInfo;
import io.sustc.dto.DanmuRecord;
import io.sustc.dto.RegisterUserReq;
import io.sustc.dto.UserRecord;
import io.sustc.dto.UserRecord.Identity;
import io.sustc.dto.VideoRecord;
import io.sustc.service.DanmuService;
import io.sustc.service.UserService;
import io.sustc.service.impl.importer.ImportException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BulkImporterTest extends IntegrationTest {

    /** Indexes, constraints (with their validation state) and non-internal triggers. */
    private static final String CATALOG =
            """
            select 'index ' || indexname || ': ' || indexdef
            from pg_indexes where schemaname = current_schema()
            union all
            select 'constraint ' || t.relname || '.' || c.conname || ': ' || pg_get_constraintdef(c.oid)
                   || case when c.convalidated then '' else ' (not validated)' end
            from pg_constraint c join pg_class t on t.oid = c.conrelid
            where c.connamespace = current_schema()::regnamespace
            union all
            select 'trigger ' || t.relname || '.' || g.tgname || ': ' || pg_get_triggerdef(g.oid)
                   || ' enabled=' || g.tgenabled::text
            from pg_trigger g join pg_class t on t.oid = g.tgrelid
            where not g.tgisinternal and t.relnamespace = current_schema()::regnamespace
            order by 1
            """;

    private static final String[] TABLES = {
        "users",
        "followings",
        "videos",
        "viewers",
        "videolikes",
        "coins",
        "favorites",
        "danmus",
        "danmulikes",
        "video_stats",
        "video_coviews"
    };

    @Autowired
    private UserService userService;

    @Autowired
    private DanmuService danmuService;

    private List<String> catalogBefore;

    @BeforeEach
    void captureCatalog() {
        catalogBefore = jdbc.queryForList(CATALOG, String.class);
    }

    private record Dataset(List<DanmuRecord> danmus, List<UserRecord> users, List<VideoRecord> videos) {}

    private static Dataset dataset() {
        List<UserRecord> users = List.of(
                user(5, "alice", Identity.USER, "10005", "", 7, 11, 1000),
                user(7, "bob", Identity.USER, "", "wx-bob", 5),
                user(11, "carol\t\\ \"quoted\"\n第二行\r", Identity.USER, "", "", 5, 7),
                user(1000, "root", Identity.SUPERUSER, "", "", new long[0]));
        List<VideoRecord> videos = List.of(
                video(
                        "BV1aaaaaaaaa",
                        5,
                        "alice",
                        123.456f,
                        "first\tvideo",
                        ts("2023-12-01 10:00:00.123456"),
                        1000L,
                        v -> {
                            v.setLike(new long[] {7, 11});
                            v.setCoin(new long[] {7});
                            v.setFavorite(new long[] {11, 1000});
                            v.setViewerMids(new long[] {7, 11, 1000});
                            v.setViewTime(new float[] {0.1f, 61.7f, 123.456f});
                        }),
                video("BV1bbbbbbbbb", 7, "bob", 33.3f, null, null, null, v -> {
                    v.setViewerMids(new long[] {11, 5});
                    v.setViewTime(new float[] {1.25f, 33.3f});
                }),
                video("BV1ccccccccc", 11, "carol", 10f, "", ts("2023-12-02 08:30:00"), 1000L, v -> {
                    // COPY text-format specials, a CRLF and a character outside the BMP
                    v.setTitle("\\. ends COPY data\r\nand \\N is NULL \uD83D\uDE00");
                    v.setLike(new long[] {5});
                }));
        users.get(1).setSign("\\N");
        users.get(3).setSign("");
        List<DanmuRecord> danmus = List.of(
                danmu("BV1aaaaaaaaa", 7, 1.1f, "hello \\ world", ts("2023-12-03 01:02:03.5"), 5, 11),
                danmu("BV1aaaaaaaaa", 11, 99.99f, "弹幕", ts("2023-12-03 01:02:04")),
                danmu("BV1bbbbbbbbb", 5, 0f, "first", ts("2023-12-04 00:00:00"), 7));
        return new Dataset(danmus, users, videos);
    }

    @Test
    void importStoresEveryRecordExactly() {
        Dataset data = dataset();

        databaseService.importData(data.danmus(), data.users(), data.videos());

        for (UserRecord u : data.users()) {
            Map<String, Object> row = jdbc.queryForMap("select * from users where mid = ?", u.getMid());
            assertThat(row)
                    .containsEntry("name", u.getName())
                    .containsEntry("sex", u.getSex())
                    .containsEntry("birthday", u.getBirthday().isEmpty() ? null : u.getBirthday())
                    .containsEntry("level", (int) u.getLevel())
                    .containsEntry("coin", u.getCoin())
                    .containsEntry("sign", u.getSign())
                    .containsEntry("identity", u.getIdentity().name())
                    .containsEntry("password", u.getPassword())
                    // empty OIDC ids are stored as NULL so that they do not collide
                    .containsEntry("qq", u.getQq().isEmpty() ? null : u.getQq())
                    .containsEntry("wechat", u.getWechat().isEmpty() ? null : u.getWechat());
            assertThat(jdbc.queryForList(
                            "select followee_mid from followings where follower_mid = ? order by 1",
                            Long.class,
                            u.getMid()))
                    .containsExactlyElementsOf(sorted(u.getFollowing()));
        }
        for (VideoRecord v : data.videos()) {
            Map<String, Object> row = jdbc.queryForMap("select * from videos where bv = ?", v.getBv());
            assertThat(row)
                    .containsEntry("title", v.getTitle())
                    .containsEntry("owner_mid", v.getOwnerMid())
                    .containsEntry("owner_name", v.getOwnerName())
                    .containsEntry("commit_time", v.getCommitTime())
                    .containsEntry("review_time", v.getReviewTime())
                    .containsEntry("reviewer", v.getReviewer())
                    .containsEntry("public_time", v.getPublicTime())
                    // exactly the float, widened: no decimal rounding on the way in
                    .containsEntry("duration", (double) v.getDuration())
                    .containsEntry("description", v.getDescription());
            assertThat(mids("videolikes", v.getBv())).containsExactlyElementsOf(sorted(v.getLike()));
            assertThat(mids("coins", v.getBv())).containsExactlyElementsOf(sorted(v.getCoin()));
            assertThat(mids("favorites", v.getBv())).containsExactlyElementsOf(sorted(v.getFavorite()));
            long[] viewers = v.getViewerMids() == null ? new long[0] : v.getViewerMids();
            for (int i = 0; i < viewers.length; i++) {
                assertThat(jdbc.queryForObject(
                                "select view_time from viewers where bv = ? and mid = ?",
                                Double.class,
                                v.getBv(),
                                viewers[i]))
                        .isEqualTo((double) v.getViewTime()[i]);
            }
            assertThat(count("select count(*) from viewers where bv = ?", v.getBv()))
                    .isEqualTo(viewers.length);
        }
        // danmu ids follow the input order, starting from 1
        for (int i = 0; i < data.danmus().size(); i++) {
            DanmuRecord d = data.danmus().get(i);
            long id = i + 1;
            assertThat(jdbc.queryForMap("select bv, mid, time, content, post_time from danmus where id = ?", id))
                    .containsEntry("bv", d.getBv())
                    .containsEntry("mid", d.getMid())
                    .containsEntry("time", (double) d.getTime())
                    .containsEntry("content", d.getContent())
                    .containsEntry("post_time", d.getPostTime());
            assertThat(jdbc.queryForList("select mid from danmulikes where danmu_id = ? order by 1", Long.class, id))
                    .containsExactlyElementsOf(sorted(d.getLikedBy()));
        }
        assertThat(count("select count(*) from danmus")).isEqualTo(data.danmus().size());
        assertThat(count("select count(*) from video_stats"))
                .isEqualTo(data.videos().size());
        assertThat(jdbc.queryForObject(
                        "select array[viewers, likes, coins, favorites, danmus]::text from video_stats where bv = ?",
                        String.class,
                        "BV1aaaaaaaaa"))
                .isEqualTo("{3,2,1,2,2}");
        assertThat(jdbc.queryForObject(
                        "select viewers from video_coviews where bv = ? and other_bv = ?",
                        Integer.class,
                        "BV1aaaaaaaaa",
                        "BV1bbbbbbbbb"))
                .isEqualTo(1);
        DerivedTables.assertConsistent(jdbc);
    }

    @Test
    void importRestoresTheSchemaAndTheIdentitySequences() {
        Dataset data = dataset();

        databaseService.importData(data.danmus(), data.users(), data.videos());

        assertThat(jdbc.queryForList(CATALOG, String.class)).isEqualTo(catalogBefore);
        assertThat(jdbc.queryForList(
                        "select relname from pg_stat_user_tables where schemaname = current_schema()"
                                + " and relname = any(?) and (last_vacuum is null or last_analyze is null)",
                        String.class,
                        (Object) TABLES))
                .as("tables without VACUUM (ANALYZE)")
                .isEmpty();
        // New ids continue after the imported ones.
        long registered = userService.register(RegisterUserReq.builder()
                .name("new")
                .password("pw")
                .sex(RegisterUserReq.Gender.UNKNOWN)
                .build());
        assertThat(registered).isEqualTo(1001);
        long danmu = danmuService.sendDanmu(new AuthInfo(7, "pw7", null, null), "BV1aaaaaaaaa", "after import", 2);
        assertThat(danmu).isEqualTo(data.danmus().size() + 1);
        // and the derived tables are maintained again
        assertThat(count("select danmus from video_stats where bv = ?", "BV1aaaaaaaaa"))
                .isEqualTo(3);
        DerivedTables.assertConsistent(jdbc);
    }

    @Test
    void importRefusesNonEmptyTables() {
        long existing = fx.user("existing");
        Dataset data = dataset();

        assertThatThrownBy(() -> databaseService.importData(data.danmus(), data.users(), data.videos()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForList("select mid from users", Long.class)).containsExactly(existing);
        assertThat(jdbc.queryForList(CATALOG, String.class)).isEqualTo(catalogBefore);
    }

    @Test
    void aFailedForeignKeyValidationLeavesAnEmptyCompleteSchema() {
        Dataset bad = dataset();
        bad.videos().get(2).setCoin(new long[] {424242});

        assertThatThrownBy(() -> databaseService.importData(bad.danmus(), bad.users(), bad.videos()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("constraints")
                .hasStackTraceContaining("validate foreign keys of coins");

        assertAuditTrailOfFailedAttempt(bad.users().size());
        assertEmptyWithCompleteSchema();
    }

    @Test
    void aDuplicateKeyLeavesAnEmptyCompleteSchema() {
        Dataset data = dataset();
        List<UserRecord> users = new ArrayList<>(data.users());
        users.add(user(7, "bob again", Identity.USER, "", "", new long[0]));

        assertThatThrownBy(() -> databaseService.importData(data.danmus(), users, data.videos()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("indexes");

        assertAuditTrailOfFailedAttempt(users.size());
        assertEmptyWithCompleteSchema();
    }

    /**
     * A COPY fails while other loads are still running: a user without a name (NOT NULL) next to a
     * large followings list. Every load is rolled back or truncated, and no connection leaks.
     */
    @Test
    void aFailedLoadLeavesAnEmptyCompleteSchema() {
        Dataset data = dataset();
        List<UserRecord> users = new ArrayList<>(data.users());
        UserRecord nameless = user(77, "x", Identity.USER, "", "", new long[0]);
        nameless.setName(null);
        users.add(nameless);
        long[] many = new long[300_000];
        for (int i = 0; i < many.length; i++) {
            many[i] = 1_000_000 + i;
        }
        users.get(0).setFollowing(many);

        assertThatThrownBy(() -> databaseService.importData(data.danmus(), users, data.videos()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("load");

        // the users COPY was rolled back, so the attempt left no INSERT records
        assertAuditTrailOfFailedAttempt(0);
        assertEmptyWithCompleteSchema();
    }

    /** Deferrable and NOT VALID constraints come back as they were. */
    @Test
    void deferrableAndNotValidConstraintsSurviveTheImport() {
        String[] change = {
            "alter table users drop constraint users_qq_key",
            "alter table users add constraint users_qq_key unique (qq) deferrable initially deferred",
            "alter table viewers alter constraint viewers_mid_fkey deferrable initially deferred",
            "alter table danmus add constraint danmus_mid_extra_fkey foreign key (mid) references users (mid) not valid"
        };
        String[] undo = {
            "alter table danmus drop constraint danmus_mid_extra_fkey",
            "alter table viewers alter constraint viewers_mid_fkey not deferrable",
            "alter table users drop constraint users_qq_key",
            "alter table users add constraint users_qq_key unique (qq)"
        };
        jdbc.batchUpdate(change);
        try {
            List<String> changed = jdbc.queryForList(CATALOG, String.class);
            Dataset data = dataset();

            databaseService.importData(data.danmus(), data.users(), data.videos());

            assertThat(jdbc.queryForList(CATALOG, String.class)).isEqualTo(changed);
            assertThat(changed)
                    .anyMatch(c -> c.contains("users_qq_key") && c.contains("DEFERRABLE INITIALLY DEFERRED"))
                    .anyMatch(c -> c.contains("danmus_mid_extra_fkey") && c.endsWith("(not validated)"));
        } finally {
            databaseService.truncate();
            jdbc.batchUpdate(undo);
        }
        assertThat(jdbc.queryForList(CATALOG, String.class)).isEqualTo(catalogBefore);
    }

    /** A dropped identity column stays in pg_attribute; the sequence reset must skip it. */
    @Test
    void aDroppedIdentityColumnDoesNotBreakTheImport() {
        jdbc.execute("alter table danmus add column scratch bigint generated always as identity");
        jdbc.execute("alter table danmus drop column scratch");
        Dataset data = dataset();

        databaseService.importData(data.danmus(), data.users(), data.videos());

        assertThat(count("select count(*) from danmus")).isEqualTo(data.danmus().size());
    }

    /** Objects that cannot be recreated exactly are refused before anything is changed. */
    @Test
    void unsupportedSchemaObjectsAreRefusedBeforeAnythingChanges() {
        jdbc.execute("comment on index users_qq_key is 'would be lost'");
        try {
            List<String> catalog = jdbc.queryForList(CATALOG, String.class);
            Dataset data = dataset();

            assertThatThrownBy(() -> databaseService.importData(data.danmus(), data.users(), data.videos()))
                    .isInstanceOf(ImportException.class)
                    .hasMessageContaining("index users_qq_key has a comment");

            assertThat(jdbc.queryForList(CATALOG, String.class)).isEqualTo(catalog);
            assertThat(count("select count(*) from users")).isZero();
        } finally {
            jdbc.execute("comment on index users_qq_key is null");
        }
    }

    /**
     * The users audit log is not an imported table: the cleanup leaves the failed attempt's INSERT
     * records in place and records its own TRUNCATE.
     */
    private void assertAuditTrailOfFailedAttempt(int insertedUsers) {
        assertThat(count("select count(*) from log_users where operation = 'INSERT'"))
                .isEqualTo(insertedUsers);
        assertThat(jdbc.queryForObject(
                        "select operation from log_users order by stamp desc, ctid desc limit 1", String.class))
                .isEqualTo("TRUNCATE");
        assertThat(count("select count(*) from log_users where operation = 'TRUNCATE' and mid is null"))
                .isGreaterThanOrEqualTo(2);
    }

    private void assertEmptyWithCompleteSchema() {
        for (String table : TABLES) {
            assertThat(count("select count(*) from " + table)).as(table).isZero();
        }
        assertThat(jdbc.queryForList(CATALOG, String.class)).isEqualTo(catalogBefore);
        // the next import works
        Dataset data = dataset();
        databaseService.importData(data.danmus(), data.users(), data.videos());
        assertThat(count("select count(*) from users")).isEqualTo(data.users().size());
        DerivedTables.assertConsistent(jdbc);
    }

    // -------------------------------------------------------------------------------------------

    private List<Long> mids(String table, String bv) {
        return jdbc.queryForList("select mid from " + table + " where bv = ? order by 1", Long.class, bv);
    }

    private static List<Long> sorted(long[] values) {
        return values == null
                ? List.of()
                : java.util.Arrays.stream(values).sorted().boxed().toList();
    }

    private static Timestamp ts(String value) {
        return Timestamp.valueOf(value);
    }

    private static UserRecord user(
            long mid, String name, Identity identity, String qq, String wechat, long... following) {
        UserRecord u = new UserRecord();
        u.setMid(mid);
        u.setName(name);
        u.setSex(mid % 2 == 0 ? "女" : "男");
        u.setBirthday(mid == 5 ? "2月29日" : "");
        u.setLevel((short) (mid % 7));
        u.setCoin((int) (mid * 3));
        u.setSign("sign of " + name);
        u.setIdentity(identity);
        u.setPassword("pw" + mid);
        u.setQq(qq);
        u.setWechat(wechat);
        u.setFollowing(following);
        return u;
    }

    private static VideoRecord video(
            String bv,
            long owner,
            String ownerName,
            float duration,
            String description,
            Timestamp reviewTime,
            Long reviewer,
            Consumer<VideoRecord> interactions) {
        VideoRecord v = new VideoRecord();
        v.setBv(bv);
        v.setTitle("title of " + bv);
        v.setOwnerMid(owner);
        v.setOwnerName(ownerName);
        v.setCommitTime(ts("2023-11-30 23:59:59.999999"));
        v.setReviewTime(reviewTime);
        v.setReviewer(reviewer);
        v.setPublicTime(reviewTime == null ? null : ts("2023-12-01 12:00:00"));
        v.setDuration(duration);
        v.setDescription(description);
        interactions.accept(v);
        return v;
    }

    private static DanmuRecord danmu(
            String bv, long mid, float time, String content, Timestamp postTime, long... likedBy) {
        DanmuRecord d = new DanmuRecord();
        d.setBv(bv);
        d.setMid(mid);
        d.setTime(time);
        d.setContent(content);
        d.setPostTime(postTime);
        d.setLikedBy(likedBy);
        return d;
    }
}
