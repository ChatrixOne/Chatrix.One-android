package eu.siacs.conversations.ui.chatrix;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import androidx.annotation.Nullable;
import eu.siacs.conversations.Config;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Chatrix.One: loudness levels of a voice message for {@link WaveformSeekBar}. The audio file is
 * decoded once on a background thread; the result is kept in memory and as a tiny file in the
 * private app cache so scrolling stays smooth.
 */
public final class Waveforms {

    public interface Callback {
        void onWaveform(@Nullable float[] levels);
    }

    /** Number of stored levels; the view merges them into however many bars fit. */
    private static final int BINS = 64;

    /** Longer recordings are not decoded (they keep the flat row of bars). */
    private static final long MAX_DURATION_US = 15L * 60 * 1_000_000;

    private static final long TIMEOUT_US = 10_000;

    private static final LruCache<String, float[]> MEMORY = new LruCache<>(256);
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Waveforms() {}

    /** Calls back on the main thread; immediately if already known. Null if not decodable. */
    public static void get(final Context context, final File file, final Callback callback) {
        final File cacheDir = new File(context.getCacheDir(), "chatrix_waveforms");
        final String key = key(file);
        final float[] cached = MEMORY.get(key);
        if (cached != null) {
            callback.onWaveform(cached);
            return;
        }
        EXECUTOR.execute(
                () -> {
                    float[] levels = MEMORY.get(key);
                    if (levels == null) {
                        levels = readDiskCache(cacheDir, key);
                    }
                    if (levels == null && file.exists()) {
                        levels = decode(file);
                        if (levels != null) {
                            writeDiskCache(cacheDir, key, levels);
                        }
                    }
                    if (levels != null) {
                        MEMORY.put(key, levels);
                    }
                    final float[] result = levels;
                    MAIN.post(() -> callback.onWaveform(result));
                });
    }

    private static String key(final File file) {
        return file.getAbsolutePath() + ':' + file.length() + ':' + file.lastModified();
    }

    @Nullable
    private static File cacheFile(final File dir, final String key) {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return null;
        }
        return new File(dir, Integer.toHexString(key.hashCode()) + ".wf");
    }

    @Nullable
    private static float[] readDiskCache(final File dir, final String key) {
        final File file = cacheFile(dir, key);
        if (file == null || file.length() != BINS) {
            return null;
        }
        try {
            final byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            final float[] levels = new float[BINS];
            for (int i = 0; i < BINS; ++i) {
                levels[i] = (bytes[i] & 0xff) / 255f;
            }
            return levels;
        } catch (final Exception e) {
            return null;
        }
    }

    private static void writeDiskCache(final File dir, final String key, final float[] levels) {
        final File file = cacheFile(dir, key);
        if (file == null) {
            return;
        }
        final byte[] bytes = new byte[levels.length];
        for (int i = 0; i < levels.length; ++i) {
            bytes[i] = (byte) Math.round(Math.max(0f, Math.min(1f, levels[i])) * 255f);
        }
        try {
            java.nio.file.Files.write(file.toPath(), bytes);
        } catch (final Exception e) {
            Log.d(Config.LOGTAG, "could not cache waveform", e);
        }
    }

    /** Peak level per bin, scaled so the loudest bin is 1. */
    @Nullable
    private static float[] decode(final File file) {
        final MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); ++i) {
                final MediaFormat f = extractor.getTrackFormat(i);
                final String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    format = f;
                    break;
                }
            }
            if (format == null || !format.containsKey(MediaFormat.KEY_DURATION)) {
                return null;
            }
            final long durationUs = format.getLong(MediaFormat.KEY_DURATION);
            if (durationUs <= 0 || durationUs > MAX_DURATION_US) {
                return null;
            }
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            final float[] peaks = new float[BINS];
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int idle = 0;
            while (!outputDone && idle < 500) {
                if (!inputDone) {
                    final int in = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (in >= 0) {
                        final ByteBuffer buffer = codec.getInputBuffer(in);
                        final int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(
                                    in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                final int out = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    final MediaFormat f = codec.getOutputFormat();
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (out >= 0) {
                    idle = 0;
                    final ByteBuffer buffer = codec.getOutputBuffer(out);
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        final ShortBuffer pcm = buffer.order(ByteOrder.nativeOrder()).asShortBuffer();
                        final int frames = pcm.remaining() / Math.max(1, channels);
                        for (int frame = 0; frame < frames; ++frame) {
                            final long t =
                                    info.presentationTimeUs + frame * 1_000_000L / sampleRate;
                            final int bin =
                                    (int) Math.min(BINS - 1, Math.max(0, t * BINS / durationUs));
                            for (int c = 0; c < channels; ++c) {
                                final float v =
                                        Math.abs(pcm.get(frame * channels + c)) / 32768f;
                                if (v > peaks[bin]) {
                                    peaks[bin] = v;
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else {
                    ++idle;
                }
            }
            float max = 0;
            for (final float p : peaks) {
                max = Math.max(max, p);
            }
            if (max <= 0) {
                return null;
            }
            for (int i = 0; i < BINS; ++i) {
                // square root keeps quiet parts visible next to loud ones
                peaks[i] = (float) Math.sqrt(peaks[i] / max);
            }
            return peaks;
        } catch (final Exception e) {
            Log.d(Config.LOGTAG, "could not decode waveform of " + file, e);
            return null;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (final Exception ignored) {
                }
                codec.release();
            }
            extractor.release();
        }
    }
}
