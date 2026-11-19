package io.sustc.service.impl.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.sustc.dto.VideoRecord;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.copy.CopyIn;
import org.postgresql.util.ByteStreamWriter;

/**
 * The co-view rows that the importer computes, from both branches of {@link DerivedData}: the
 * dense matrix (catalogs of up to {@value DerivedData#DENSE_MATRIX_MAX_VIDEOS} videos, the only
 * branch the benchmark data reaches) and the hash map used above that.
 */
class DerivedDataTest {

    /** Collects the COPY text that the writer produces. */
    private static final class CapturedCopy implements CopyIn {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        @Override
        public void writeToCopy(byte[] buf, int off, int siz) {
            out.write(buf, off, siz);
        }

        @Override
        public void writeToCopy(ByteStreamWriter from) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void flushCopy() {}

        @Override
        public long endCopy() {
            return -1;
        }

        @Override
        public int getFieldCount() {
            return 3;
        }

        @Override
        public int getFormat() {
            return 0;
        }

        @Override
        public int getFieldFormat(int field) {
            return 0;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void cancelCopy() {}

        @Override
        public long getHandledRowCount() {
            return -1;
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3})
    void bothBranchesWriteExactlyTheBruteForcePairs(long seed) throws SQLException {
        Random random = new Random(seed);
        int videoCount = 60;
        List<Set<Long>> viewers = new ArrayList<>();
        for (int i = 0; i < videoCount; i++) {
            viewers.add(new LinkedHashSet<>());
        }
        Map<String, Integer> expected = new HashMap<>();
        for (long mid = 1; mid <= 400; mid++) {
            int k = random.nextInt(8);
            Set<Integer> watched = new LinkedHashSet<>();
            while (watched.size() < k) {
                watched.add(random.nextInt(videoCount));
            }
            for (int a : watched) {
                viewers.get(a).add(mid);
                for (int b : watched) {
                    if (a != b) {
                        expected.merge("BV" + a + "\t" + "BV" + b, 1, Integer::sum);
                    }
                }
            }
        }
        List<VideoRecord> videos = new ArrayList<>();
        for (int i = 0; i < videoCount; i++) {
            VideoRecord v = new VideoRecord();
            v.setBv("BV" + i);
            long[] mids = viewers.get(i).stream().mapToLong(Long::longValue).toArray();
            // a video nobody watched may come with a null array or an empty one
            v.setViewerMids(mids.length == 0 && i % 2 == 0 ? null : mids);
            v.setViewTime(new float[mids.length]);
            videos.add(v);
        }

        Map<String, Integer> dense = coviews(videos, Integer.MAX_VALUE);
        Map<String, Integer> sparse = coviews(videos, 0);

        assertThat(dense).isEqualTo(expected);
        assertThat(sparse).isEqualTo(expected);
    }

    private static Map<String, Integer> coviews(List<VideoRecord> videos, int denseMatrixMaxVideos)
            throws SQLException {
        CapturedCopy copy = new CapturedCopy();
        CopyRowWriter writer = new CopyRowWriter(copy);
        DerivedData.writeCoviews(videos, writer, denseMatrixMaxVideos);
        writer.finish();
        Map<String, Integer> rows = new HashMap<>();
        for (String line : copy.out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.isEmpty()) {
                int tab = line.lastIndexOf('\t');
                Integer previous = rows.put(line.substring(0, tab), Integer.parseInt(line.substring(tab + 1)));
                assertThat(previous).as("duplicate row %s", line).isNull();
            }
        }
        return rows;
    }
}
