package io.sustc.service.impl.importer;

import io.sustc.dto.DanmuRecord;
import io.sustc.dto.VideoRecord;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the rows of the derived tables {@code video_stats} and {@code video_coviews} from the
 * records being imported, so the bulk load writes them directly instead of aggregating the loaded
 * tables afterwards (the co-view self-join alone produces ~20M intermediate rows on the benchmark
 * data). The {@code derived_*} triggers in {@code sql/build.sql} keep both tables exact from then
 * on, and the integration tests compare them with a from-scratch recomputation.
 */
final class DerivedData {

    /** Up to this many videos the co-view counts are accumulated in a dense matrix. */
    static final int DENSE_MATRIX_MAX_VIDEOS = 2048;

    private DerivedData() {}

    /** One row per video: bv, viewers, view_time_sum, likes, coins, favorites, danmus. */
    static void writeVideoStats(List<VideoRecord> videos, List<DanmuRecord> danmus, CopyRowWriter w)
            throws SQLException {
        Map<String, Integer> danmuCounts = new HashMap<>();
        for (DanmuRecord d : danmus) {
            danmuCounts.merge(d.getBv(), 1, Integer::sum);
        }
        for (VideoRecord v : videos) {
            long[] viewers = orEmpty(v.getViewerMids());
            float[] viewTimes = v.getViewTime();
            // Summed in load order, which is also the order a sequential scan would add them in.
            double viewTimeSum = 0;
            for (int i = 0; i < viewers.length; i++) {
                viewTimeSum += viewTimes[i];
            }
            w.value(v.getBv())
                    .value(viewers.length)
                    .value(viewTimeSum)
                    .value(orEmpty(v.getLike()).length)
                    .value(orEmpty(v.getCoin()).length)
                    .value(orEmpty(v.getFavorite()).length)
                    .value(danmuCounts.getOrDefault(v.getBv(), 0))
                    .endRow();
        }
    }

    /**
     * One row per ordered pair of distinct videos with at least one common viewer: bv, other_bv,
     * viewers. Each user who watched k videos contributes to k * (k - 1) pairs.
     */
    static void writeCoviews(List<VideoRecord> videos, CopyRowWriter w) throws SQLException {
        writeCoviews(videos, w, DENSE_MATRIX_MAX_VIDEOS);
    }

    /**
     * Like {@link #writeCoviews(List, CopyRowWriter)}, with the largest catalog that still uses the
     * dense matrix as a parameter, so that tests can run both branches on the same input. The two
     * branches write the same rows, in a different order.
     */
    static void writeCoviews(List<VideoRecord> videos, CopyRowWriter w, int denseMatrixMaxVideos) throws SQLException {
        int n = videos.size();
        Map<Long, int[]> watchedBy = watchedVideos(videos);
        if (n <= denseMatrixMaxVideos) {
            int[] counts = new int[n * n];
            for (int[] watched : watchedBy.values()) {
                int k = watched[0];
                for (int x = 1; x <= k; x++) {
                    int row = watched[x] * n;
                    for (int y = 1; y <= k; y++) {
                        if (watched[y] != watched[x]) {
                            counts[row + watched[y]]++;
                        }
                    }
                }
            }
            for (int a = 0; a < n; a++) {
                for (int b = 0; b < n; b++) {
                    int count = counts[a * n + b];
                    if (count > 0) {
                        w.value(videos.get(a).getBv())
                                .value(videos.get(b).getBv())
                                .value(count)
                                .endRow();
                    }
                }
            }
        } else {
            Map<Long, int[]> counts = new HashMap<>();
            for (int[] watched : watchedBy.values()) {
                int k = watched[0];
                for (int x = 1; x <= k; x++) {
                    for (int y = 1; y <= k; y++) {
                        if (watched[y] != watched[x]) {
                            counts.computeIfAbsent((long) watched[x] * n + watched[y], key -> new int[1])[0]++;
                        }
                    }
                }
            }
            for (Map.Entry<Long, int[]> e : counts.entrySet()) {
                w.value(videos.get((int) (e.getKey() / n)).getBv())
                        .value(videos.get((int) (e.getKey() % n)).getBv())
                        .value(e.getValue()[0])
                        .endRow();
            }
        }
    }

    /** For every viewer: {@code [count, videoIndex...]}, video indexes into {@code videos}. */
    private static Map<Long, int[]> watchedVideos(List<VideoRecord> videos) {
        Map<Long, int[]> watchedBy = new HashMap<>();
        for (int i = 0; i < videos.size(); i++) {
            for (long mid : orEmpty(videos.get(i).getViewerMids())) {
                int[] watched = watchedBy.get(mid);
                if (watched == null || watched[0] + 1 == watched.length) {
                    watched = watched == null ? new int[8] : Arrays.copyOf(watched, watched.length * 2);
                    watchedBy.put(mid, watched);
                }
                watched[++watched[0]] = i;
            }
        }
        return watchedBy;
    }

    static long[] orEmpty(long[] values) {
        return values == null ? new long[0] : values;
    }
}
