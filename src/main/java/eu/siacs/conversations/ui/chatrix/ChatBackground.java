package eu.siacs.conversations.ui.chatrix;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;
import android.view.View;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;
import androidx.preference.PreferenceManager;
import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.persistance.DatabaseBackend;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Chatrix.One: background behind the chat (soft green glow, plain, or the user's own picture). */
public final class ChatBackground {

    public static final String PREF_KEY = "chatrix_chat_background";
    public static final String GLOW = "glow";
    public static final String PLAIN = "plain";
    public static final String CUSTOM = "custom";

    private static final String FILE_NAME = "chatrix_chat_background.jpg";

    /**
     * A background is at most this big (32 MB decoded): a long screenshot is shrunk to fit.
     * Android refuses to draw bitmaps over 100 MB, which would crash every time the chat shows.
     */
    private static final long MAX_PIXELS = 8L * 1024 * 1024;

    private ChatBackground() {}

    public static String mode(final Context context) {
        final String mode =
                PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_KEY, GLOW);
        return mode == null ? GLOW : mode;
    }

    /** The default picture (from settings) for a null uuid, else the picture of that chat. */
    public static File file(final Context context, @Nullable final String conversationUuid) {
        if (conversationUuid == null) {
            return new File(context.getFilesDir(), FILE_NAME);
        }
        return new File(context.getFilesDir(), ChatBackgroundFiles.name(conversationUuid));
    }

    /**
     * Once per app start, off the main thread: deletes per-chat pictures whose chat is gone (its
     * account was deleted). A database error stops the clean-up, it never deletes more.
     */
    public static void sweepLater(final Context context) {
        final Context app = context.getApplicationContext();
        final Thread thread =
                new Thread(
                        () -> {
                            try {
                                final int deleted =
                                        ChatBackgroundFiles.sweep(
                                                app.getFilesDir(),
                                                uuid -> chatExists(app, uuid),
                                                System.currentTimeMillis());
                                if (deleted > 0) {
                                    Log.d(Config.LOGTAG, "deleted " + deleted + " chat backgrounds");
                                }
                            } catch (final RuntimeException e) {
                                if (BuildConfig.DEBUG) {
                                    throw e; // most likely our own bug: crash loudly while developing
                                }
                                Log.w(Config.LOGTAG, "chat background clean-up failed", e);
                            }
                        },
                        "chatrix-background-sweep");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /** True if the conversations table has this uuid (also archived chats and invalid addresses). */
    private static boolean chatExists(final Context context, final String uuid) {
        try (final var cursor =
                DatabaseBackend.getInstance(context)
                        .getReadableDatabase()
                        .query(
                                Conversation.TABLENAME,
                                new String[] {Conversation.UUID},
                                Conversation.UUID + "=?",
                                new String[] {uuid},
                                null,
                                null,
                                null)) {
            return cursor.getCount() > 0;
        }
    }

    /**
     * Sets the background of this chat on its view: its own choice if it has one, otherwise the
     * default from settings. Main thread only.
     */
    public static void apply(final View container, @Nullable final Conversation conversation) {
        final Context context = container.getContext();
        apply(
                container,
                conversation == null ? null : conversation.getUuid(),
                conversation == null ? null : conversation.getChatrixBackground(),
                ContextCompat.getColor(context, R.color.chatrix_window_bg),
                ContextCompat.getColor(context, R.color.chatrix_logo));
    }

    /**
     * A picture is never decoded on the main thread (ultrareview 2026-11-26: switching between
     * two chats with their own pictures decoded a full-screen JPEG on every switch). A picture in
     * {@link #PICTURES} is set at once; any other is decoded in the background while the plain
     * window colour shows, and is set only if the view still waits for it, so a quick switch
     * never puts one chat's picture behind another.
     */
    @VisibleForTesting
    static void apply(
            final View container,
            @Nullable final String uuid,
            @Nullable final String own,
            @ColorInt final int windowBg,
            @ColorInt final int accent) {
        final Context context = container.getContext();
        final Object token = new Object();
        WAITING.put(container, token);
        final boolean perChat = own != null;
        final String mode = perChat ? own : mode(context);
        if (PLAIN.equals(mode)) {
            container.setBackground(null);
            return;
        }
        final File file = file(context, perChat ? uuid : null);
        if (!CUSTOM.equals(mode) || !file.isFile()) {
            container.setBackground(new GlowDrawable(windowBg, accent));
            return;
        }
        final boolean night = isNight(context);
        final String path = file.getAbsolutePath();
        final long stamp = file.lastModified();
        final Picture cached = PICTURES.get(path);
        if (cached != null && cached.stamp() == stamp) {
            container.setBackground(new PictureDrawable(cached.bitmap(), windowBg, night));
            return;
        }
        container.setBackground(new ColorDrawable(windowBg));
        decoder.execute(
                () -> {
                    final Bitmap bitmap = decode(path, stamp);
                    new Handler(Looper.getMainLooper())
                            .post(
                                    () -> {
                                        if (WAITING.get(container) != token) {
                                            return; // the view shows another chat by now
                                        }
                                        WAITING.remove(container);
                                        container.setBackground(
                                                bitmap != null
                                                        ? new PictureDrawable(
                                                                bitmap, windowBg, night)
                                                        : new GlowDrawable(windowBg, accent));
                                    });
                });
    }

    private static boolean isNight(final Context context) {
        return (context.getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** Copies (and downsizes) the picked picture into private app storage. Run off the UI thread. */
    public static boolean save(final Context context, final Uri uri) {
        return save(context, uri, file(context, null));
    }

    /** Same as {@link #save(Context, Uri)} but into the given file (e.g. a chat's own picture). */
    public static boolean save(final Context context, final Uri uri, final File target) {
        final DisplayMetrics dm = context.getResources().getDisplayMetrics();
        final int targetSize = Math.max(dm.widthPixels, dm.heightPixels);
        try {
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return false;
            }
            int sample = 1;
            while (Math.min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetSize / 2) {
                sample *= 2;
            }
            sample = ImageBudget.fit(bounds.outWidth, bounds.outHeight, sample, MAX_PIXELS);
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                bitmap = BitmapFactory.decodeStream(in, null, options);
            }
            if (bitmap == null) {
                return false;
            }
            int rotation = 0;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    rotation = new ExifInterface(in).getRotationDegrees();
                }
            } catch (final IOException ignored) {
            }
            if (rotation != 0) {
                final Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                bitmap =
                        Bitmap.createBitmap(
                                bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }
            final File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out);
            }
            return tmp.renameTo(target);
        } catch (final IOException | RuntimeException | OutOfMemoryError e) {
            // runs on its own thread: an odd picture must not take the app down
            Log.w(Config.LOGTAG, "could not save chat background", e);
            return false;
        }
    }

    /** Decodes a saved picture and keeps it in {@link #PICTURES}. Null if it cannot be read. */
    @Nullable
    private static Bitmap decode(final String path, final long stamp) {
        final Picture cached = PICTURES.get(path);
        if (cached != null && cached.stamp() == stamp) {
            return cached.bitmap(); // decoded meanwhile for another view
        }
        try {
            // pictures saved before the size limit can be far too big to draw
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize =
                    ImageBudget.fit(bounds.outWidth, bounds.outHeight, 1, MAX_PIXELS);
            final Bitmap bitmap = BitmapFactory.decodeFile(path, options);
            if (bitmap != null) {
                PICTURES.put(path, new Picture(stamp, bitmap));
            }
            return bitmap;
        } catch (final OutOfMemoryError e) {
            Log.w(Config.LOGTAG, "chat background too big to show", e);
            return null;
        } catch (final RuntimeException e) {
            if (BuildConfig.DEBUG) {
                throw e; // most likely our own bug: crash loudly while developing
            }
            Log.w(Config.LOGTAG, "could not show chat background", e);
            return null;
        }
    }

    private record Picture(long stamp, Bitmap bitmap) {}

    /**
     * Decoded pictures by file path, checked against the file's time (a new picture under the
     * same name is decoded again). At most 1/8 of the app's memory and 64 MB: about 3 to 6
     * screen-sized pictures.
     */
    private static final LruCache<String, Picture> PICTURES =
            new LruCache<>((int) Math.min(Runtime.getRuntime().maxMemory() / 8, 64L << 20)) {
                @Override
                protected int sizeOf(final String path, final Picture picture) {
                    return picture.bitmap().getAllocationByteCount();
                }
            };

    /** The latest apply() per view: a decode that finishes after a newer apply is dropped. */
    private static final Map<View, Object> WAITING = new WeakHashMap<>();

    private static final ExecutorService DECODER =
            Executors.newSingleThreadExecutor(
                    r -> {
                        final Thread t = new Thread(r, "chatrix-background-decode");
                        t.setDaemon(true);
                        return t;
                    });

    /** Where pictures are decoded; tests replace it. */
    @VisibleForTesting static Executor decoder = DECODER;

    @VisibleForTesting
    static void forgetPictures() {
        PICTURES.evictAll();
        WAITING.clear();
    }

    /** Soft emerald glow, like the chat mock-up on chatrix.one. */
    private static final class GlowDrawable extends Drawable {
        private final int background;
        private final int accent;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        GlowDrawable(final int background, final int accent) {
            this.background = background;
            this.accent = accent;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final Rect b = getBounds();
            canvas.drawColor(background);
            final float radius = Math.max(b.width(), b.height()) * 0.55f;
            glow(canvas, b.left + b.width() * 0.15f, b.top + b.height() * 0.25f, radius, 0x16);
            glow(canvas, b.left + b.width() * 0.9f, b.top + b.height() * 0.75f, radius, 0x12);
        }

        private void glow(
                final Canvas canvas, final float x, final float y, final float r, final int alpha) {
            final int center = (accent & 0x00ffffff) | (alpha << 24);
            paint.setShader(
                    new RadialGradient(
                            x, y, r, center, accent & 0x00ffffff, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, r, paint);
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }

    /** The user's picture, centre-cropped, with a light veil so messages stay readable. */
    private static final class PictureDrawable extends Drawable {
        private final Bitmap bitmap;
        private final int veil;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Matrix matrix = new Matrix();

        PictureDrawable(final Bitmap bitmap, final int windowBg, final boolean night) {
            this.bitmap = bitmap;
            this.veil = (windowBg & 0x00ffffff) | ((night ? 0x73 : 0x40) << 24);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final Rect b = getBounds();
            if (b.isEmpty()) {
                return;
            }
            final float scale =
                    Math.max(
                            (float) b.width() / bitmap.getWidth(),
                            (float) b.height() / bitmap.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate(
                    b.left + (b.width() - bitmap.getWidth() * scale) / 2f,
                    b.top + (b.height() - bitmap.getHeight() * scale) / 2f);
            canvas.save();
            canvas.clipRect(b);
            canvas.drawBitmap(bitmap, matrix, paint);
            canvas.restore();
            canvas.drawColor(veil);
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
