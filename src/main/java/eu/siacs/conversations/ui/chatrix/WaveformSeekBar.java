package eu.siacs.conversations.ui.chatrix;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatSeekBar;
import java.io.File;

/**
 * Chatrix.One: voice message progress drawn as sound-wave bars. It is still a SeekBar, so
 * AudioPlayer keeps handling play position, seeking and colours exactly as before; only the
 * drawing changes. Played bars use the progress tint, the rest the same colour faded.
 */
public class WaveformSeekBar extends AppCompatSeekBar {

    private static final int FALLBACK_COLOR = 0xff00a87a;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private final float barWidth;
    private final float barGap;
    private final float minBarHeight;

    @Nullable private File file;
    @Nullable private float[] levels;

    public WaveformSeekBar(@NonNull Context context) {
        this(context, null);
    }

    public WaveformSeekBar(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, android.R.attr.seekBarStyle);
    }

    public WaveformSeekBar(
            @NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        final float density = getResources().getDisplayMetrics().density;
        barWidth = 3f * density;
        barGap = 2f * density;
        minBarHeight = 3f * density;
        setThumb(null);
        setBackground(null);
        setSplitTrack(false);
    }

    /** Shows the waveform of this audio file (decoded in the background, then cached). */
    public void setAudioFile(@Nullable final File file) {
        if (file != null && file.equals(this.file) && levels != null) {
            return;
        }
        this.file = file;
        this.levels = null;
        invalidate();
        if (file == null) {
            return;
        }
        Waveforms.get(
                getContext(),
                file,
                result -> {
                    // the view may have been recycled for another message meanwhile
                    if (result != null && file.equals(this.file)) {
                        this.levels = result;
                        invalidate();
                    }
                });
    }

    @Override
    protected synchronized void onDraw(@NonNull Canvas canvas) {
        final float left = getPaddingLeft();
        final float right = getWidth() - getPaddingRight();
        final float width = right - left;
        if (width <= 0) {
            return;
        }
        final int count = Math.max(1, (int) ((width + barGap) / (barWidth + barGap)));
        final float step = count > 1 ? (width - barWidth) / (count - 1) : 0;
        final float top = getPaddingTop();
        final float height = getHeight() - getPaddingBottom() - top;
        final float centerY = top + height / 2f;
        final float max = getMax();
        final float played = max <= 0 ? 0 : getProgress() / max;

        final int color = progressColor();
        final int faded = (color & 0x00ffffff) | (((color >>> 24) * 2 / 5) << 24);
        final float radius = barWidth / 2f;
        final boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;

        for (int i = 0; i < count; ++i) {
            final float level = level(i, count);
            final float h = Math.max(minBarHeight, level * height);
            final float x = rtl ? right - barWidth - i * step : left + i * step;
            final boolean isPlayed = (i + 0.5f) / count <= played;
            paint.setColor(isPlayed ? color : faded);
            bar.set(x, centerY - h / 2f, x + barWidth, centerY + h / 2f);
            canvas.drawRoundRect(bar, radius, radius, paint);
        }
    }

    /** Bar height 0..1: the loudest bin within this bar's share of the recording. */
    private float level(final int i, final int count) {
        final float[] levels = this.levels;
        if (levels == null || levels.length == 0) {
            return 0f; // not decoded (yet): flat row of short bars
        }
        final int from = i * levels.length / count;
        final int to = Math.max(from + 1, (i + 1) * levels.length / count);
        float peak = 0;
        for (int j = from; j < to && j < levels.length; ++j) {
            peak = Math.max(peak, levels[j]);
        }
        return peak;
    }

    private int progressColor() {
        final ColorStateList tint = getProgressTintList();
        if (tint == null) {
            return FALLBACK_COLOR;
        }
        return tint.getColorForState(getDrawableState(), tint.getDefaultColor());
    }
}
