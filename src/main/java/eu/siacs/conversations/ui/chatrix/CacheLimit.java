package eu.siacs.conversations.ui.chatrix;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chatrix.One: keeps a cache folder (link previews, waveforms) under a size and a file-count
 * limit. Android may clear the cache folder when storage runs low, but nothing guarantees it, so
 * without a limit these folders would only grow.
 *
 * <p>An entry is all files that share a name up to the first dot ({@code <hash>.txt} and {@code
 * <hash>.jpg} go together), so a preview never loses only its picture. Sizes count whole 4 KB
 * blocks, which is what a small file really takes on disk. When a limit is passed, the entries
 * used longest ago (newest file time; readers touch their files on use) are removed until the
 * folder is down to three quarters of both limits, so it does not trim again at the next write.
 * Plain Java without Android, so unit tests can check it.
 */
public final class CacheLimit {

    static final long BLOCK = 4096;

    private final long maxBytes;
    private final int maxEntries;
    private final int checkEvery;
    private int writesSinceCheck;
    private boolean checkedOnce;

    public CacheLimit(final long maxBytes, final int maxEntries, final int checkEvery) {
        this.maxBytes = maxBytes;
        this.maxEntries = maxEntries;
        this.checkEvery = checkEvery;
    }

    /**
     * Call after each write into {@code dir}. Looks at the folder at the first write after the
     * app started and then every {@code checkEvery} writes. Returns the number of removed entries.
     */
    public synchronized int afterWrite(final File dir) {
        if (checkedOnce && ++writesSinceCheck < checkEvery) {
            return 0;
        }
        checkedOnce = true;
        writesSinceCheck = 0;
        return trim(dir, maxBytes, maxEntries);
    }

    /** Marks a cache file as just used, so its entry is removed last. */
    public static void touch(final File file) {
        //noinspection ResultOfMethodCallIgnored
        file.setLastModified(System.currentTimeMillis());
    }

    /** Disk space a file of this length takes: whole blocks, at least one. */
    static long onDisk(final long length) {
        return Math.max(1, (length + BLOCK - 1) / BLOCK) * BLOCK;
    }

    /** Three quarters of a limit, rounded down, without overflow for Long.MAX_VALUE. */
    static long threeQuarters(final long value) {
        return value / 4 * 3 + value % 4 * 3 / 4;
    }

    /** The entry a file name belongs to: the name up to its first dot. */
    static String stem(final String fileName) {
        final int dot = fileName.indexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    /**
     * Removes the entries used longest ago if the folder is over a limit, until it holds at most
     * three quarters of both. Sub-folders are left alone. Returns the number of removed entries.
     */
    static int trim(final File dir, final long maxBytes, final int maxEntries) {
        final File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        final Map<String, Entry> entries = new HashMap<>();
        long bytes = 0;
        for (final File file : files) {
            if (!file.isFile()) {
                continue;
            }
            final long size = onDisk(file.length());
            bytes += size;
            entries.computeIfAbsent(stem(file.getName()), k -> new Entry()).add(file, size);
        }
        if (bytes <= maxBytes && entries.size() <= maxEntries) {
            return 0;
        }
        final long targetBytes = threeQuarters(maxBytes);
        final long targetEntries = threeQuarters(maxEntries);
        final List<Entry> oldestFirst = new ArrayList<>(entries.values());
        oldestFirst.sort(Comparator.comparingLong(e -> e.lastUsed));
        int count = entries.size();
        int removed = 0;
        for (final Entry entry : oldestFirst) {
            if (bytes <= targetBytes && count <= targetEntries) {
                break;
            }
            for (final File file : entry.files) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            bytes -= entry.bytes;
            count--;
            removed++;
        }
        return removed;
    }

    private static final class Entry {
        private final List<File> files = new ArrayList<>(2);
        private long bytes;
        private long lastUsed = Long.MIN_VALUE;

        private void add(final File file, final long size) {
            files.add(file);
            bytes += size;
            lastUsed = Math.max(lastUsed, file.lastModified());
        }
    }
}
