package eu.siacs.conversations.ui.chatrix;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Chatrix.One: size and count limit of the link preview and waveform cache folders. */
public class CacheLimitTest {

    private static final long T0 = 1_800_000_000_000L;

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void underTheLimitNothingIsRemoved() throws IOException {
        file("a.txt", 10, T0);
        file("b.txt", 10, T0 + 1000);
        Assert.assertEquals(0, CacheLimit.trim(folder.getRoot(), 1_000_000, 10));
        Assert.assertEquals(2, names().size());
    }

    @Test
    public void oldestEntriesGoFirstTogetherWithTheirPicture() throws IOException {
        for (int i = 0; i < 8; ++i) {
            file("p" + i + ".txt", 100, T0 + i * 1000L);
            file("p" + i + ".jpg", 50_000, T0 + i * 1000L);
        }
        // 8 entries over a limit of 6: down to 3/4 of 6 = 4 entries, the 4 newest
        Assert.assertEquals(4, CacheLimit.trim(folder.getRoot(), Long.MAX_VALUE, 6));
        final List<String> left = names();
        for (int i = 0; i < 8; ++i) {
            final boolean kept = i >= 4;
            Assert.assertEquals("p" + i + ".txt", kept, left.contains("p" + i + ".txt"));
            Assert.assertEquals("p" + i + ".jpg", kept, left.contains("p" + i + ".jpg"));
        }
    }

    @Test
    public void aTouchedEntryCountsAsNew() throws IOException {
        for (int i = 0; i < 8; ++i) {
            file("w" + i + ".wf", 64, T0 + i * 1000L);
        }
        // the oldest one was just used: it stays, the next oldest go
        Assert.assertTrue(new File(folder.getRoot(), "w0.wf").setLastModified(T0 + 60_000));
        CacheLimit.trim(folder.getRoot(), Long.MAX_VALUE, 6);
        Assert.assertTrue(names().contains("w0.wf"));
        Assert.assertFalse(names().contains("w1.wf"));
    }

    @Test
    public void threeQuartersRoundsDownWithoutOverflow() {
        Assert.assertEquals(4, CacheLimit.threeQuarters(6));
        Assert.assertEquals(0, CacheLimit.threeQuarters(1));
        Assert.assertEquals(75, CacheLimit.threeQuarters(100));
        Assert.assertTrue(CacheLimit.threeQuarters(Long.MAX_VALUE) > Long.MAX_VALUE / 4 * 3 - 1);
        Assert.assertTrue(CacheLimit.threeQuarters(Long.MAX_VALUE) < Long.MAX_VALUE);
    }

    @Test
    public void sizeCountsWholeBlocks() throws IOException {
        Assert.assertEquals(4096, CacheLimit.onDisk(0));
        Assert.assertEquals(4096, CacheLimit.onDisk(64));
        Assert.assertEquals(8192, CacheLimit.onDisk(4097));
        // 100 waveforms of 64 bytes take 400 KB on disk, over a 200 KB limit
        for (int i = 0; i < 100; ++i) {
            file("w" + i + ".wf", 64, T0 + i);
        }
        CacheLimit.trim(folder.getRoot(), 200 * 1024, 10_000);
        Assert.assertTrue(names().size() * 4096L <= 150 * 1024);
    }

    @Test
    public void subFoldersAndMissingFoldersAreLeftAlone() throws IOException {
        Assert.assertTrue(new File(folder.getRoot(), "keep").mkdir());
        for (int i = 0; i < 5; ++i) {
            file("x" + i + ".txt", 10, T0 + i);
        }
        CacheLimit.trim(folder.getRoot(), Long.MAX_VALUE, 2);
        Assert.assertTrue(new File(folder.getRoot(), "keep").isDirectory());
        Assert.assertEquals(0, CacheLimit.trim(new File(folder.getRoot(), "none"), 1, 1));
    }

    @Test
    public void looksAtTheFolderOnlyEveryNWrites() throws IOException {
        final CacheLimit limit = new CacheLimit(Long.MAX_VALUE, 4, 3);
        for (int i = 0; i < 10; ++i) {
            file("e" + i + ".txt", 10, T0 + i);
        }
        Assert.assertEquals("first write after start checks", 7, limit.afterWrite(folder.getRoot()));
        for (int i = 10; i < 20; ++i) {
            file("e" + i + ".txt", 10, T0 + i);
        }
        Assert.assertEquals(0, limit.afterWrite(folder.getRoot()));
        Assert.assertEquals(0, limit.afterWrite(folder.getRoot()));
        Assert.assertTrue(limit.afterWrite(folder.getRoot()) > 0);
    }

    @Test
    public void randomFoldersEndWithinTheLimitsKeepingTheNewest() throws IOException {
        final Random random = new Random(7);
        for (int round = 0; round < 40; ++round) {
            final File dir = folder.newFolder("r" + round);
            final Map<String, Long> times = new HashMap<>();
            final int n = 1 + random.nextInt(60);
            for (int i = 0; i < n; ++i) {
                final long time = T0 + random.nextInt(1_000_000) * 1000L;
                times.put("k" + i, time);
                file(dir, "k" + i + ".txt", random.nextInt(9000), time);
                if (random.nextBoolean()) {
                    file(dir, "k" + i + ".jpg", random.nextInt(200_000), time - random.nextInt(5000));
                }
            }
            final long maxBytes = 4096L * (1 + random.nextInt(80));
            final int maxEntries = 1 + random.nextInt(40);
            final long before = usage(dir);
            CacheLimit.trim(dir, maxBytes, maxEntries);
            final List<String> left = new ArrayList<>();
            for (final File f : listOf(dir)) {
                left.add(CacheLimit.stem(f.getName()));
            }
            final long after = usage(dir);
            if (before > maxBytes || times.size() > maxEntries) {
                Assert.assertTrue("bytes", after <= CacheLimit.threeQuarters(maxBytes));
                Assert.assertTrue(
                        "entries",
                        left.stream().distinct().count() <= CacheLimit.threeQuarters(maxEntries));
            } else {
                Assert.assertEquals(before, after);
            }
            // no removed entry was used later than one that stayed
            long oldestKept = Long.MAX_VALUE;
            for (final String k : left) {
                oldestKept = Math.min(oldestKept, times.get(k));
            }
            for (final Map.Entry<String, Long> e : times.entrySet()) {
                if (!left.contains(e.getKey())) {
                    Assert.assertTrue(e.getKey(), e.getValue() <= oldestKept);
                }
            }
            // a preview never keeps its picture without its text, or the other way round
            for (final File f : listOf(dir)) {
                final String k = CacheLimit.stem(f.getName());
                Assert.assertTrue(new File(dir, k + ".txt").exists());
            }
        }
    }

    private long usage(final File dir) {
        long total = 0;
        for (final File f : listOf(dir)) {
            total += CacheLimit.onDisk(f.length());
        }
        return total;
    }

    private static List<File> listOf(final File dir) {
        final File[] files = dir.listFiles(File::isFile);
        return files == null ? List.of() : List.of(files);
    }

    private List<String> names() {
        final List<String> names = new ArrayList<>();
        for (final File f : listOf(folder.getRoot())) {
            names.add(f.getName());
        }
        return names;
    }

    private void file(final String name, final int size, final long modified) throws IOException {
        file(folder.getRoot(), name, size, modified);
    }

    private static void file(final File dir, final String name, final int size, final long modified)
            throws IOException {
        final File file = new File(dir, name);
        Files.write(file.toPath(), new byte[size]);
        Assert.assertTrue(file.setLastModified(modified));
    }
}
