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
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;
import androidx.preference.PreferenceManager;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Chatrix.One: background behind the chat (soft green glow, plain, or the user's own picture). */
public final class ChatBackground {

    public static final String PREF_KEY = "chatrix_chat_background";
    public static final String GLOW = "glow";
    public static final String PLAIN = "plain";
    public static final String CUSTOM = "custom";

    private static final String FILE_NAME = "chatrix_chat_background.jpg";

    private ChatBackground() {}

    public static String mode(final Context context) {
        final String mode =
                PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_KEY, GLOW);
        return mode == null ? GLOW : mode;
    }

    private static File file(final Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    /** Returns the drawable for the current setting, or null for the plain theme background. */
    @Nullable
    public static Drawable create(final Context context) {
        final String mode = mode(context);
        final int windowBg = ContextCompat.getColor(context, R.color.chatrix_window_bg);
        final int accent = ContextCompat.getColor(context, R.color.chatrix_logo);
        if (CUSTOM.equals(mode)) {
            final Bitmap bitmap = load(context);
            if (bitmap != null) {
                return new PictureDrawable(bitmap, windowBg, isNight(context));
            }
            return new GlowDrawable(windowBg, accent);
        } else if (PLAIN.equals(mode)) {
            return null;
        } else {
            return new GlowDrawable(windowBg, accent);
        }
    }

    private static boolean isNight(final Context context) {
        return (context.getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** Copies (and downsizes) the picked picture into private app storage. Run off the UI thread. */
    public static boolean save(final Context context, final Uri uri) {
        final DisplayMetrics dm = context.getResources().getDisplayMetrics();
        final int target = Math.max(dm.widthPixels, dm.heightPixels);
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
            while (Math.min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target / 2) {
                sample *= 2;
            }
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
            final File tmp = new File(context.getFilesDir(), FILE_NAME + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out);
            }
            return tmp.renameTo(file(context));
        } catch (final IOException | SecurityException | OutOfMemoryError e) {
            Log.w(Config.LOGTAG, "could not save chat background", e);
            return false;
        }
    }

    @Nullable
    private static Bitmap load(final Context context) {
        final File file = file(context);
        if (!file.exists()) {
            return null;
        }
        final long stamp = file.lastModified();
        synchronized (ChatBackground.class) {
            if (cached != null && cachedStamp == stamp) {
                return cached;
            }
            try {
                cached = BitmapFactory.decodeFile(file.getAbsolutePath());
                cachedStamp = stamp;
                return cached;
            } catch (final OutOfMemoryError e) {
                return null;
            }
        }
    }

    private static Bitmap cached;
    private static long cachedStamp;

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
