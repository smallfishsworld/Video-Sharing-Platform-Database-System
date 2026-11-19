import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.sustc.dto.AuthInfo;
import io.sustc.dto.DanmuRecord;
import io.sustc.dto.PostVideoReq;
import io.sustc.dto.RegisterUserReq;
import io.sustc.dto.UserRecord;
import io.sustc.dto.VideoRecord;
import io.sustc.service.DanmuService;
import io.sustc.service.DatabaseService;
import io.sustc.service.UserService;
import io.sustc.service.VideoService;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Micro-benchmarks and stress tests of the benchmark's eleven write operations, for whichever
 * implementation of io.sustc.service.impl is on the class path (run.sh puts the classes of one
 * runner jar there). The services are created as in the runner: Spring component scan of
 * io.sustc.service.impl, a Hikari pool with the runner's settings.
 *
 * <p>Modes:
 *
 * <ul>
 *   <li>steady: import the benchmark data, optionally settle, run every operation {@code --warmup} times
 *       (discarded), then {@code --ops} times on {@code --clients} threads; report throughput,
 *       p50 and p95 per operation.
 *   <li>count: run every operation {@code --ops} times on one thread with server-side prepared
 *       statements off, and count the protocol messages the driver sends: Parse (statements),
 *       Sync and simple queries (round trips).
 *   <li>stress: concurrent coin donations by one user, concurrent like and follow toggles of one
 *       pair, and concurrent deletions of two accounts with crossing interactions; report the
 *       invariant violations and exceptions.
 * </ul>
 *
 * <p>Load limits: at most {@value #MAX_CLIENTS} client threads and {@value #MAX_OPS} calls per
 * operation, and only a loopback database unless {@code --allow-remote} is given.
 */
public final class WriteBench {

    static final int MAX_CLIENTS = 16;
    static final int MAX_OPS = 5000;
    /** The benchmark's clock start, 2023-12-30T12:00:00+08:00, as run_bench.sh passes it. */
    static final Instant BENCH_NOW = Instant.parse("2023-12-30T04:00:00Z");

    static final List<String> OPS = List.of(
            "sendDanmu", "likeDanmu", "coinVideo", "likeVideo", "collectVideo", "postVideo",
            "register", "updateVideoInfo", "reviewVideo", "deleteVideo", "deleteVideoWithViews", "deleteAccount",
            "follow");

    final Map<String, String> opt;
    final Data data;
    final Random rnd;
    final String runTag;
    VideoService videos;
    UserService users;
    DanmuService danmus;
    DatabaseService database;
    DataSource ds;
    String url;

    WriteBench(Map<String, String> opt, Data data) {
        this.opt = opt;
        this.data = data;
        this.rnd = new Random(Long.parseLong(opt.getOrDefault("seed", "1")));
        this.runTag = opt.getOrDefault("tag", "wb");
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> o = options(args);
        String url = o.getOrDefault("url", "jdbc:postgresql://localhost:5432/sustc");
        requireLoopback(url, o.containsKey("allow-remote"));
        int clients = Integer.parseInt(o.getOrDefault("clients", "1"));
        int ops = Integer.parseInt(o.getOrDefault("ops", "1000"));
        int warmup = Integer.parseInt(o.getOrDefault("warmup", "500"));
        if (clients < 1 || clients > MAX_CLIENTS || ops < 1 || ops > MAX_OPS || warmup < 0 || warmup > MAX_OPS) {
            throw new IllegalArgumentException("clients must be 1.." + MAX_CLIENTS + ", ops and warmup 0.." + MAX_OPS);
        }
        String mode = o.getOrDefault("mode", "steady");
        Data data = Data.load(Paths.get(o.getOrDefault("data", "sustc-runner/data")));
        WriteBench b = new WriteBench(o, data);
        b.url = url;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode);
        out.put("label", o.get("label"));
        out.put("clients", clients);
        out.put("ops", ops);
        out.put("warmup", warmup);
        out.put("seed", o.getOrDefault("seed", "1"));
        out.put("options", new TreeMap<>(o));
        out.put("started", Instant.now().toString());
        b.start(mode.equals("count"));
        if (!o.containsKey("no-import")) {
            long t0 = System.nanoTime();
            b.database.importData(data.danmus, data.users, data.videos);
            out.put("import_ms", (System.nanoTime() - t0) / 1_000_000);
        }
        if (o.containsKey("settle")) {
            out.put("settle", b.settle());
        }
        switch (mode) {
            case "steady" -> out.put("results", b.steady(clients, warmup, ops));
            case "count" -> out.put("results", b.count(ops));
            case "stress" -> out.put("results", b.stress(Integer.parseInt(o.getOrDefault("trials", "50"))));
            default -> throw new IllegalArgumentException("unknown mode " + mode);
        }
        out.put("finished", Instant.now().toString());
        String json = Json.write(out);
        if (o.containsKey("out")) {
            Files.writeString(Paths.get(o.get("out")), json + "\n");
        }
        System.out.println(json);
        System.exit(0);
    }

    // Setup ---------------------------------------------------------------------------------------

    void start(boolean counting) {
        HikariConfig cfg = new HikariConfig();
        String u = url;
        if (counting) {
            u += (u.contains("?") ? "&" : "?") + "prepareThreshold=0";
        }
        cfg.setJdbcUrl(u);
        cfg.setUsername(opt.getOrDefault("user", "sustc"));
        cfg.setPassword(opt.getOrDefault("password", "sustc"));
        // The runner's application.yml settings; the pool size is Hikari's default (10).
        cfg.setConnectionTimeout(180000);
        cfg.setLeakDetectionThreshold(120000);
        HikariDataSource hikari = new HikariDataSource(cfg);
        ds = hikari;
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean("dataSource", DataSource.class, () -> hikari);
        ctx.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(hikari));
        ctx.registerBean(NamedParameterJdbcTemplate.class, () -> new NamedParameterJdbcTemplate(hikari));
        ctx.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(hikari));
        ctx.scan("io.sustc.service.impl");
        ctx.refresh();
        videos = ctx.getBean(VideoService.class);
        users = ctx.getBean(UserService.class);
        danmus = ctx.getBean(DanmuService.class);
        database = ctx.getBean(DatabaseService.class);
    }

    /** CHECKPOINT as postgres, then wait until the kernel's dirty pages drop below 64 MB. */
    Map<String, Object> settle() throws Exception {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("dirty_kb_before", dirtyKb());
        long t0 = System.nanoTime();
        Process p = new ProcessBuilder("sudo", "-n", "-u", "postgres", "psql", "-Atqc", "checkpoint")
                .redirectErrorStream(true)
                .start();
        String msg = new String(p.getInputStream().readAllBytes()).trim();
        s.put("checkpoint_exit", p.waitFor());
        if (!msg.isEmpty()) {
            s.put("checkpoint_output", msg);
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (dirtyKb() > 65536 && System.nanoTime() < deadline) {
            Thread.sleep(500);
        }
        s.put("settle_ms", (System.nanoTime() - t0) / 1_000_000);
        s.put("dirty_kb_after", dirtyKb());
        return s;
    }

    static long dirtyKb() throws Exception {
        for (String line : Files.readAllLines(Paths.get("/proc/meminfo"))) {
            if (line.startsWith("Dirty:")) {
                return Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
        }
        return -1;
    }

    // Workload --------------------------------------------------------------------------------------

    /** One call of an operation with its arguments; returns 1 for done, 0 for refused. */
    interface Call {
        int run();
    }

    /** Arguments for n calls of every operation, in the benchmark's step order. */
    Map<String, List<Call>> workload(int n, String phase) {
        Map<String, List<Call>> w = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        w.put("sendDanmu", pairs(n, used, "d", (v, u) -> {
            float time = rnd.nextFloat() * v.getDuration() * 0.99f;
            String content = runTag + " " + phase + " danmu " + used.size();
            return () -> danmus.sendDanmu(data.auth(u), v.getBv(), content, time) >= 0 ? 1 : 0;
        }));
        List<Call> likeDanmu = new ArrayList<>();
        while (likeDanmu.size() < n) {
            int d = rnd.nextInt(data.danmus.size());
            VideoRecord v = data.byBv.get(data.danmus.get(d).getBv());
            if (v == null || !data.visible(v) || v.getViewerMids().length == 0) {
                continue;
            }
            long u = v.getViewerMids()[rnd.nextInt(v.getViewerMids().length)];
            long id = d + 1L; // the importers assign danmu ids in list order, as the benchmark's cases expect
            if (!data.deleted.contains(u) && used.add("ld" + id + "/" + u)) {
                data.actors.add(u);
                likeDanmu.add(() -> danmus.likeDanmu(data.auth(u), id) ? 1 : 0);
            }
        }
        w.put("likeDanmu", likeDanmu);
        w.put("coinVideo", pairs(n, used, "c", (v, u) -> {
            if (contains(v.getCoin(), u) || data.coinsLeft.getOrDefault(u, 0) <= 0) {
                return null;
            }
            data.coinsLeft.merge(u, -1, Integer::sum);
            return () -> videos.coinVideo(data.auth(u), v.getBv()) ? 1 : 0;
        }));
        w.put("likeVideo", pairs(n, used, "l", (v, u) -> () -> videos.likeVideo(data.auth(u), v.getBv()) ? 1 : 0));
        w.put("collectVideo", pairs(n, used, "f", (v, u) -> () -> videos.collectVideo(data.auth(u), v.getBv()) ? 1 : 0));

        String[] posted = new String[n];
        long[] poster = new long[n];
        Timestamp publicTime = Timestamp.from(BENCH_NOW.plus(Duration.ofDays(30)));
        List<Call> post = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i;
            do {
                poster[i] = data.regular.get(rnd.nextInt(data.regular.size())).getMid();
            } while (data.deleted.contains(poster[i]));
            data.actors.add(poster[i]);
            PostVideoReq req = new PostVideoReq(runTag + " " + phase + " video " + i, "write bench", 120.5f, publicTime);
            post.add(() -> (posted[k] = videos.postVideo(data.auth(poster[k]), req)) != null ? 1 : 0);
        }
        w.put("postVideo", post);
        long[] registered = new long[n];
        List<Call> register = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i;
            RegisterUserReq req = new RegisterUserReq(
                    "pw" + i, null, null, runTag + "_" + phase + "_" + i,
                    RegisterUserReq.Gender.values()[i % 3], (1 + i % 12) + "月" + (1 + i % 28) + "日", "write bench");
            register.add(() -> (registered[k] = users.register(req)) > 0 ? 1 : 0);
        }
        w.put("register", register);
        List<Call> update = new ArrayList<>();
        List<Call> review = new ArrayList<>();
        List<Call> deleteVideo = new ArrayList<>();
        AuthInfo superuser = data.auth(data.superuser.getMid());
        for (int i = 0; i < n; i++) {
            int k = i;
            PostVideoReq req = new PostVideoReq(runTag + " " + phase + " video " + i + " v2", "edited", 120.5f, publicTime);
            update.add(() -> posted[k] != null && videos.updateVideoInfo(data.auth(poster[k]), posted[k], req) ? 1 : 0);
            review.add(() -> posted[k] != null && videos.reviewVideo(superuser, posted[k]) ? 1 : 0);
            deleteVideo.add(() -> posted[k] != null && videos.deleteVideo(data.auth(poster[k]), posted[k]) ? 1 : 0);
        }
        w.put("updateVideoInfo", update);
        w.put("reviewVideo", review);
        w.put("deleteVideo", deleteVideo);
        // The benchmark deletes imported videos, each with about 20,000 views; there are only 44,
        // so a run deletes a few of them, after the warm-up.
        List<Call> deleteImported = new ArrayList<>();
        int imported = phase.equals("warm") ? 0 : Integer.parseInt(opt.getOrDefault("imported-videos", "8"));
        List<VideoRecord> candidates = new ArrayList<>(data.videos);
        java.util.Collections.shuffle(candidates, rnd);
        for (VideoRecord v : candidates) {
            if (deleteImported.size() == imported) {
                break;
            }
            if (data.byMid.containsKey(v.getOwnerMid()) && !data.deleted.contains(v.getOwnerMid())) {
                data.actors.add(v.getOwnerMid());
                deleteImported.add(() -> videos.deleteVideo(data.auth(v.getOwnerMid()), v.getBv()) ? 1 : 0);
            }
        }
        w.put("deleteVideoWithViews", deleteImported);
        List<Call> follow = new ArrayList<>();
        while (follow.size() < n) {
            UserRecord a = data.regular.get(rnd.nextInt(data.regular.size()));
            UserRecord b = data.regular.get(rnd.nextInt(data.regular.size()));
            if (a.getMid() == b.getMid() || data.deleted.contains(a.getMid()) || data.deleted.contains(b.getMid())
                    || !used.add("fo" + a.getMid() + "/" + b.getMid())) {
                continue;
            }
            data.actors.add(a.getMid());
            data.actors.add(b.getMid());
            follow.add(() -> users.follow(data.auth(a.getMid()), b.getMid()) ? 1 : 0);
        }
        w.put("follow", follow);
        List<Call> deleteAccount = new ArrayList<>();
        while (deleteAccount.size() < n) {
            UserRecord u = data.regular.get(rnd.nextInt(data.regular.size()));
            // Video owners are left out: deleting one cascades to that owner's videos and their views.
            if (data.actors.contains(u.getMid()) || data.owners.contains(u.getMid()) || !data.deleted.add(u.getMid())) {
                continue;
            }
            deleteAccount.add(() -> users.deleteAccount(data.auth(u.getMid()), u.getMid()) ? 1 : 0);
        }
        w.put("deleteAccount", deleteAccount);
        return w;
    }

    interface PairCall {
        Call make(VideoRecord v, long user);
    }

    /** n calls on distinct (visible video, user who watched it, not its owner) pairs. */
    List<Call> pairs(int n, Set<String> used, String kind, PairCall make) {
        List<Call> calls = new ArrayList<>();
        int misses = 0;
        while (calls.size() < n) {
            VideoRecord v = data.visibleVideos.get(rnd.nextInt(data.visibleVideos.size()));
            long[] viewers = v.getViewerMids();
            if (viewers.length == 0) {
                continue;
            }
            long u = viewers[rnd.nextInt(viewers.length)];
            if (u == v.getOwnerMid() || data.deleted.contains(u) || !used.add(kind + v.getBv() + "/" + u)) {
                continue;
            }
            Call c = make.make(v, u);
            if (c == null) {
                if (++misses > 100 * n) {
                    throw new IllegalStateException("not enough arguments for " + kind);
                }
                continue;
            }
            data.actors.add(u);
            calls.add(c);
        }
        return calls;
    }

    static boolean contains(long[] a, long x) {
        for (long y : a) {
            if (y == x) {
                return true;
            }
        }
        return false;
    }

    // Steady state ----------------------------------------------------------------------------------

    List<Map<String, Object>> steady(int clients, int warmup, int n) throws Exception {
        Map<String, List<Call>> warm = workload(warmup, "warm");
        Map<String, List<Call>> measured = workload(n, "run");
        Set<String> only = opt.containsKey("only") ? Set.of(opt.get("only").split(",")) : Set.copyOf(OPS);
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        try {
            for (String op : OPS) {
                if (only.contains(op) && !warm.get(op).isEmpty()) {
                    runCalls(pool, clients, warm.get(op));
                }
            }
            List<Map<String, Object>> results = new ArrayList<>();
            List<String[]> ranges = new ArrayList<>();
            for (String op : OPS) {
                if (!only.contains(op) || measured.get(op).isEmpty()) {
                    continue;
                }
                String lsn0 = lsn();
                Batch b = runCalls(pool, clients, measured.get(op));
                String lsn1 = lsn();
                results.add(b.summary(op, clients));
                ranges.add(new String[] {lsn0, lsn1});
            }
            // WAL written during each operation's calls (server-wide, so other sessions count too),
            // read back with pg_waldump once the timed part is over.
            for (int i = 0; i < results.size(); i++) {
                results.get(i).putAll(walBetween(ranges.get(i)[0], ranges.get(i)[1], (int) results.get(i).get("calls")));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    String lsn() throws Exception {
        try (Connection c = ds.getConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("select pg_current_wal_insert_lsn()::text")) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** pg_waldump --stats between two LSNs, as postgres: records and bytes per call. */
    static Map<String, Object> walBetween(String from, String to, int calls) throws Exception {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("lsn_start", from);
        r.put("lsn_end", to);
        Process p = new ProcessBuilder("sudo", "-n", "-u", "postgres", "/usr/lib/postgresql/16/bin/pg_waldump",
                        "--stats", "-s", from, "-e", to, "-p", "/var/lib/postgresql/16/main/pg_wal")
                .redirectErrorStream(true)
                .start();
        String outText = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        for (String line : outText.split("\n")) {
            if (line.startsWith("Total")) {
                // Total  <records>  <record bytes> [%]  <FPI bytes> [%]  <combined bytes> [%]
                String[] f = line.replaceAll("\\[[^]]*]", " ").trim().split("\\s+");
                r.put("wal_records_per_call", round(Long.parseLong(f[1]) / (double) calls));
                r.put("wal_record_bytes_per_call", round(Long.parseLong(f[2]) / (double) calls));
                r.put("wal_fpi_bytes_per_call", round(Long.parseLong(f[3]) / (double) calls));
                r.put("wal_bytes_per_call", round(Long.parseLong(f[4]) / (double) calls));
                return r;
            }
        }
        r.put("waldump_error", outText.length() > 200 ? outText.substring(0, 200) : outText);
        return r;
    }

    static final class Batch {
        long[] nanos;
        int done;
        int refused;
        int failed;
        long wallNanos;
        Map<String, Integer> errors = new TreeMap<>();

        Map<String, Object> summary(String op, int clients) {
            long[] s = nanos.clone();
            Arrays.sort(s);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("op", op);
            r.put("clients", clients);
            r.put("calls", s.length);
            r.put("result_true", done);
            r.put("result_false", refused);
            r.put("failed", failed);
            if (!errors.isEmpty()) {
                r.put("errors", errors);
            }
            r.put("wall_ms", round(wallNanos / 1e6));
            r.put("throughput_per_s", round(s.length / (wallNanos / 1e9)));
            r.put("p50_ms", round(s[(int) Math.floor(0.50 * (s.length - 1))] / 1e6));
            r.put("p95_ms", round(s[(int) Math.floor(0.95 * (s.length - 1))] / 1e6));
            r.put("mean_ms", round(Arrays.stream(s).average().orElse(0) / 1e6));
            r.put("max_ms", round(s[s.length - 1] / 1e6));
            return r;
        }
    }

    static Batch runCalls(ExecutorService pool, int clients, List<Call> calls) throws Exception {
        Batch b = new Batch();
        b.nanos = new long[calls.size()];
        AtomicInteger next = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        CyclicBarrier start = new CyclicBarrier(clients + 1);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < clients; t++) {
            fs.add(pool.submit(() -> {
                start.await();
                for (int i; (i = next.getAndIncrement()) < calls.size(); ) {
                    long t0 = System.nanoTime();
                    try {
                        if (calls.get(i).run() == 1) {
                            done.incrementAndGet();
                        } else {
                            refused.incrementAndGet();
                        }
                    } catch (RuntimeException e) {
                        failed.incrementAndGet();
                        String key = rootMessage(e);
                        synchronized (b.errors) {
                            b.errors.merge(key, 1, Integer::sum);
                        }
                    }
                    b.nanos[i] = System.nanoTime() - t0;
                }
                return null;
            }));
        }
        start.await();
        long t0 = System.nanoTime();
        for (Future<?> f : fs) {
            f.get();
        }
        b.wallNanos = System.nanoTime() - t0;
        b.done = done.get();
        b.refused = refused.get();
        b.failed = failed.get();
        return b;
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String m = t.getClass().getSimpleName() + ": " + t.getMessage();
        m = m.split("\n")[0];
        return m.length() > 120 ? m.substring(0, 120) : m;
    }

    // Statement counts --------------------------------------------------------------------------------

    static final class Counter extends Handler {
        final AtomicLong parse = new AtomicLong();
        final AtomicLong sync = new AtomicLong();
        final AtomicLong simple = new AtomicLong();
        final Map<String, Integer> statements = new TreeMap<>();

        @Override
        public void publish(LogRecord r) {
            String m = r.getMessage();
            if (m == null) {
                return;
            }
            if (m.startsWith(" FE=> Parse")) {
                parse.incrementAndGet();
                // pgjdbc 42.3 and 42.7 both log " FE=> Parse(stmt=S,query=\"SQL\",oids={...})".
                int from = m.indexOf(",query=\"");
                int to = m.lastIndexOf("\",oids={");
                String q = from >= 0 && to > from ? m.substring(from + 8, to) : "?";
                q = q.strip().replaceAll("\\s+", " ");
                q = q.length() > 60 ? q.substring(0, 60) : q;
                synchronized (statements) {
                    statements.merge(q, 1, Integer::sum);
                }
            } else if (m.startsWith(" FE=> Sync")) {
                sync.incrementAndGet();
            } else if (m.startsWith(" FE=> SimpleQuery")) {
                simple.incrementAndGet();
            }
        }

        void reset() {
            parse.set(0);
            sync.set(0);
            simple.set(0);
            statements.clear();
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }

    List<Map<String, Object>> count(int n) throws Exception {
        Map<String, List<Call>> w = workload(n, "count");
        Counter counter = new Counter();
        Logger pg = Logger.getLogger("org.postgresql");
        pg.setLevel(Level.FINEST);
        pg.addHandler(counter);
        pg.setUseParentHandlers(false);
        List<Map<String, Object>> results = new ArrayList<>();
        for (String op : OPS) {
            if (w.get(op).isEmpty()) {
                continue;
            }
            counter.reset();
            int done = 0;
            for (Call c : w.get(op)) {
                done += c.run();
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("op", op);
            int calls = w.get(op).size();
            r.put("calls", calls);
            r.put("done", done);
            r.put("statements_per_call", round(counter.parse.get() / (double) calls));
            r.put("round_trips_per_call", round((counter.sync.get() + counter.simple.get()) / (double) calls));
            Map<String, Object> st = new LinkedHashMap<>();
            counter.statements.forEach((q, k) -> st.put(q, round(k / (double) calls)));
            r.put("statements", st);
            results.add(r);
        }
        pg.removeHandler(counter);
        return results;
    }

    // Stress ----------------------------------------------------------------------------------------

    Map<String, Object> stress(int trials) throws Exception {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("coin_double_spend", coinStress(trials));
        r.put("like_toggle", toggleStress(trials, false));
        r.put("follow_toggle", toggleStress(trials, true));
        r.put("crossing_account_deletions", deletionStress(trials));
        return r;
    }

    /**
     * A user with c coins (1 to 3) donates to 3c distinct videos from 8 threads at once. Done
     * donations must not exceed c, and the balance must drop by exactly the done donations.
     */
    Map<String, Object> coinStress(int trials) throws Exception {
        int violations = 0;
        int overspent = 0;
        int failed = 0;
        long doneTotal = 0;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int t = 0; t < trials; t++) {
                UserRecord donor = null;
                List<VideoRecord> targets = new ArrayList<>();
                while (donor == null) {
                    UserRecord c = data.regular.get(rnd.nextInt(data.regular.size()));
                    int left = data.coinsLeft.getOrDefault(c.getMid(), 0);
                    if (left < 1 || left > 3 || data.actors.contains(c.getMid()) || data.deleted.contains(c.getMid())) {
                        continue;
                    }
                    targets.clear();
                    for (VideoRecord v : data.watched.getOrDefault(c.getMid(), List.of())) {
                        if (v.getOwnerMid() != c.getMid() && !contains(v.getCoin(), c.getMid())) {
                            targets.add(v);
                        }
                    }
                    if (targets.size() >= 3 * left) {
                        donor = c;
                    }
                }
                long mid = donor.getMid();
                data.actors.add(mid);
                int coins = users.getUserInfo(mid).getCoin();
                List<Call> calls = new ArrayList<>();
                for (VideoRecord v : targets.subList(0, 3 * coins)) {
                    calls.add(() -> videos.coinVideo(data.auth(mid), v.getBv()) ? 1 : 0);
                }
                Batch b = runCalls(pool, 8, calls);
                int after = users.getUserInfo(mid).getCoin();
                doneTotal += b.done;
                failed += b.failed;
                if (b.done > coins) {
                    overspent++;
                }
                if (b.done > coins || after != coins - b.done || after < 0) {
                    violations++;
                }
                data.coinsLeft.put(mid, after);
            }
        } finally {
            pool.shutdownNow();
        }
        return Map.of("trials", trials, "trials_with_violation", violations, "trials_overspent", overspent,
                "failed_calls", failed, "done_donations", doneTotal);
    }

    /**
     * 8 threads toggle one pair 10 times each. Every call reports the state after it; in a run
     * without lost updates, (calls that report on) - (calls that report off) equals the final state.
     */
    Map<String, Object> toggleStress(int trials, boolean follow) throws Exception {
        int violations = 0;
        int failed = 0;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int t = 0; t < trials; t++) {
                long me;
                String bv = null;
                long followee = 0;
                if (follow) {
                    UserRecord a = data.regular.get(rnd.nextInt(data.regular.size()));
                    UserRecord b = data.regular.get(rnd.nextInt(data.regular.size()));
                    if (a.getMid() == b.getMid() || data.deleted.contains(a.getMid()) || data.deleted.contains(b.getMid())) {
                        t--;
                        continue;
                    }
                    me = a.getMid();
                    followee = b.getMid();
                } else {
                    VideoRecord v = data.visibleVideos.get(rnd.nextInt(data.visibleVideos.size()));
                    if (v.getViewerMids().length == 0) {
                        t--;
                        continue;
                    }
                    me = v.getViewerMids()[rnd.nextInt(v.getViewerMids().length)];
                    if (me == v.getOwnerMid() || data.deleted.contains(me)) {
                        t--;
                        continue;
                    }
                    bv = v.getBv();
                }
                boolean before = follow ? following(me, followee) : liked(me, bv);
                long fMe = me;
                long fFollowee = followee;
                String fBv = bv;
                List<Call> calls = new ArrayList<>();
                for (int i = 0; i < 80; i++) {
                    calls.add(() -> (follow ? users.follow(data.auth(fMe), fFollowee) : videos.likeVideo(data.auth(fMe), fBv)) ? 1 : 0);
                }
                Batch b = runCalls(pool, 8, calls);
                boolean after = follow ? following(me, followee) : liked(me, bv);
                failed += b.failed;
                // Each call flips the state and reports the new one: from "before", the number of
                // calls that report "on" minus those that report "off" is fixed by the final state.
                int expected = (after ? 1 : 0) - (before ? 1 : 0);
                if (b.failed > 0 || b.done - b.refused != expected) {
                    violations++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return Map.of("trials", trials, "calls_per_trial", 80, "threads", 8, "trials_with_lost_update", violations,
                "failed_calls", failed);
    }

    boolean liked(long mid, String bv) {
        return Arrays.asList(users.getUserInfo(mid).getLiked()).contains(bv);
    }

    boolean following(long mid, long followee) {
        return contains(users.getUserInfo(mid).getFollowing(), followee);
    }

    /**
     * Two accounts with crossing interactions are deleted at once, the second 0 to 8 ms after the
     * first: a liked videos X and donated to videos Y, b liked Y and donated to X. Inserted with
     * SQL (the baseline and this project use the same table and column names), so each account
     * holds 20 rows in each table.
     */
    Map<String, Object> deletionStress(int trials) throws Exception {
        int failedTrials = 0;
        int bothDone = 0;
        Map<String, Integer> errors = new TreeMap<>();
        long deadlocks0 = deadlocks();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection c = ds.getConnection()) {
            for (int t = 0; t < trials; t++) {
                RegisterUserReq ra = new RegisterUserReq("pw", null, null, runTag + "_xa_" + t, RegisterUserReq.Gender.MALE, "1月1日", "x");
                RegisterUserReq rb = new RegisterUserReq("pw", null, null, runTag + "_xb_" + t, RegisterUserReq.Gender.MALE, "1月1日", "x");
                long a = users.register(ra);
                long b = users.register(rb);
                List<String> picked = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                while (picked.size() < 40) {
                    VideoRecord v = data.visibleVideos.get(rnd.nextInt(data.visibleVideos.size()));
                    if (seen.add(v.getBv())) {
                        picked.add(v.getBv());
                    }
                }
                List<String> x = picked.subList(0, 20);
                List<String> y = picked.subList(20, 40);
                try (var ps = c.prepareStatement("insert into videolikes (bv, mid) values (?, ?)");
                        var pc = c.prepareStatement("insert into coins (bv, mid) values (?, ?)")) {
                    for (String bv : x) {
                        ps.setString(1, bv); ps.setLong(2, a); ps.addBatch();
                        pc.setString(1, bv); pc.setLong(2, b); pc.addBatch();
                    }
                    for (String bv : y) {
                        ps.setString(1, bv); ps.setLong(2, b); ps.addBatch();
                        pc.setString(1, bv); pc.setLong(2, a); pc.addBatch();
                    }
                    ps.executeBatch();
                    pc.executeBatch();
                }
                int offset = t % 9;
                CyclicBarrier go = new CyclicBarrier(2);
                Future<Boolean> fa = pool.submit(() -> {
                    go.await();
                    return users.deleteAccount(AuthInfo.builder().mid(a).password("pw").build(), a);
                });
                Future<Boolean> fb = pool.submit(() -> {
                    go.await();
                    Thread.sleep(offset);
                    return users.deleteAccount(AuthInfo.builder().mid(b).password("pw").build(), b);
                });
                int ok = 0;
                boolean failed = false;
                for (Future<Boolean> f : List.of(fa, fb)) {
                    try {
                        ok += f.get() ? 1 : 0;
                    } catch (java.util.concurrent.ExecutionException e) {
                        failed = true;
                        errors.merge(rootMessage(e), 1, Integer::sum);
                    }
                }
                if (failed) {
                    failedTrials++;
                }
                if (ok == 2) {
                    bothDone++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("trials", trials);
        r.put("offsets_ms", "0..8, trial % 9");
        r.put("trials_with_error", failedTrials);
        r.put("trials_both_deleted", bothDone);
        r.put("server_deadlocks_detected", deadlocks() - deadlocks0);
        r.put("errors", errors);
        return r;
    }

    long deadlocks() throws Exception {
        try (Connection c = ds.getConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("select deadlocks from pg_stat_database where datname = current_database()")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // Data ------------------------------------------------------------------------------------------

    static final class Data {
        List<DanmuRecord> danmus;
        List<UserRecord> users;
        List<VideoRecord> videos;
        Map<Long, UserRecord> byMid = new HashMap<>();
        Map<String, VideoRecord> byBv = new HashMap<>();
        List<VideoRecord> visibleVideos = new ArrayList<>();
        List<UserRecord> regular = new ArrayList<>();
        UserRecord superuser;
        Map<Long, Integer> coinsLeft = new HashMap<>();
        Set<Long> actors = new HashSet<>();
        Set<Long> deleted = new HashSet<>();
        Map<Long, List<VideoRecord>> watched = new HashMap<>();
        Set<Long> owners = new HashSet<>();

        @SuppressWarnings("unchecked")
        static Data load(Path dir) throws Exception {
            io.fury.ThreadSafeFury fury = io.fury.Fury.builder()
                    .requireClassRegistration(false)
                    .withLanguage(io.fury.config.Language.JAVA)
                    .withRefTracking(true)
                    .withCompatibleMode(io.fury.config.CompatibleMode.COMPATIBLE)
                    .withAsyncCompilation(true)
                    .buildThreadSafeFury();
            Data d = new Data();
            d.danmus = (List<DanmuRecord>) fury.deserialize(Files.readAllBytes(dir.resolve("import/danmu.ser")));
            d.users = (List<UserRecord>) fury.deserialize(Files.readAllBytes(dir.resolve("import/user.ser")));
            d.videos = (List<VideoRecord>) fury.deserialize(Files.readAllBytes(dir.resolve("import/video.ser")));
            for (UserRecord u : d.users) {
                d.byMid.put(u.getMid(), u);
                d.coinsLeft.put(u.getMid(), u.getCoin());
                if (u.getIdentity() == UserRecord.Identity.SUPERUSER) {
                    if (d.superuser == null) {
                        d.superuser = u;
                    }
                } else {
                    d.regular.add(u);
                }
            }
            for (VideoRecord v : d.videos) {
                d.byBv.put(v.getBv(), v);
                d.owners.add(v.getOwnerMid());
                if (d.visible(v)) {
                    d.visibleVideos.add(v);
                    for (long u : v.getViewerMids()) {
                        d.watched.computeIfAbsent(u, k -> new ArrayList<>()).add(v);
                    }
                }
            }
            d.actors.add(d.superuser.getMid());
            return d;
        }

        boolean visible(VideoRecord v) {
            return v.getReviewTime() != null
                    && v.getPublicTime() != null
                    && v.getPublicTime().toInstant().isBefore(BENCH_NOW.minus(Duration.ofDays(1)));
        }

        AuthInfo auth(long mid) {
            return AuthInfo.builder().mid(mid).password(byMid.get(mid).getPassword()).build();
        }
    }

    // Helpers ---------------------------------------------------------------------------------------

    static double round(double x) {
        return Math.round(x * 1000) / 1000.0;
    }

    static Map<String, String> options(String[] args) {
        Map<String, String> o = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument " + args[i]);
            }
            String k = args[i].substring(2);
            if (Set.of("allow-remote", "no-import", "settle").contains(k)) {
                o.put(k, "true");
            } else if (i + 1 < args.length) {
                o.put(k, args[++i]);
            } else {
                throw new IllegalArgumentException("missing value for --" + k);
            }
        }
        return o;
    }

    static void requireLoopback(String url, boolean allowRemote) throws Exception {
        String rest = url.replaceFirst("^jdbc:postgresql://", "");
        String host = rest.split("[:/?]")[0];
        if (!allowRemote && !InetAddress.getByName(host).isLoopbackAddress()) {
            throw new IllegalArgumentException("refusing non-loopback database " + host + " without --allow-remote");
        }
    }

    /** Minimal JSON writer for maps, lists, strings and numbers. */
    static final class Json {
        static String write(Object o) {
            StringBuilder sb = new StringBuilder();
            write(sb, o);
            return sb.toString();
        }

        static void write(StringBuilder sb, Object o) {
            if (o instanceof Map<?, ?> m) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    write(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
            } else if (o instanceof List<?> l) {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    write(sb, l.get(i));
                }
                sb.append(']');
            } else if (o instanceof Number || o instanceof Boolean) {
                sb.append(o);
            } else if (o == null) {
                sb.append("null");
            } else {
                String s = o.toString();
                sb.append('"');
                for (char ch : s.toCharArray()) {
                    switch (ch) {
                        case '"' -> sb.append("\\\"");
                        case '\\' -> sb.append("\\\\");
                        case '\n' -> sb.append("\\n");
                        default -> {
                            if (ch < 0x20) {
                                sb.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                            } else {
                                sb.append(ch);
                            }
                        }
                    }
                }
                sb.append('"');
            }
        }
    }
}
