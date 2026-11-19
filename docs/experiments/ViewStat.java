// Run (with the classes of a runner jar, extracted by scripts/writebench/run.sh, on the class path):
//   javac -cp "$LIB/*" ViewStat.java && java -cp ".:$LIB/*" ViewStat sustc-runner/data/import/video.ser
import io.sustc.dto.VideoRecord;
import java.nio.file.*;
import java.util.*;

/** Views per user in the benchmark's import data (video.ser), and the co-view rows a deletion touches. */
public class ViewStat {
    @SuppressWarnings("unchecked")
    public static void main(String[] a) throws Exception {
        io.fury.ThreadSafeFury fury = io.fury.Fury.builder().requireClassRegistration(false)
                .withLanguage(io.fury.config.Language.JAVA).withRefTracking(true)
                .withCompatibleMode(io.fury.config.CompatibleMode.COMPATIBLE).buildThreadSafeFury();
        List<VideoRecord> videos = (List<VideoRecord>) fury.deserialize(Files.readAllBytes(Paths.get(a[0])));
        Map<Long, Integer> perUser = new HashMap<>();
        long views = 0;
        for (VideoRecord v : videos) {
            for (long m : v.getViewerMids()) { perUser.merge(m, 1, Integer::sum); views++; }
        }
        int[] k = perUser.values().stream().mapToInt(Integer::intValue).sorted().toArray();
        int median = k[(k.length - 1) / 2];
        int max = k[k.length - 1];
        System.out.printf("videos %d, views %d, users with views %d, views per user: median %d (lower median), mean %.1f, max %d%n",
                videos.size(), views, k.length, median, views / (double) k.length, max);
        System.out.printf("co-view rows changed by deleting a user with k views, k(k-1): median user %d, heaviest %d%n",
                (long) median * (median - 1), (long) max * (max - 1));
    }
}
