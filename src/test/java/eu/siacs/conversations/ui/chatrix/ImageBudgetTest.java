package eu.siacs.conversations.ui.chatrix;

import java.util.Random;
import org.junit.Assert;
import org.junit.Test;

/** Chatrix.One: the pixel budget every decode of a foreign picture goes through. */
public class ImageBudgetTest {

    private static final long BACKGROUND = 8L * 1024 * 1024;

    @Test
    public void aLongScreenshotIsShrunkBelowTheDrawLimit() {
        // 1440 x 19000 at full size is 109 MB: Android refuses to draw that
        final int sample = ImageBudget.fit(1440, 19_000, 1, BACKGROUND);
        Assert.assertEquals(2, sample);
        Assert.assertTrue(pixels(1440, 19_000, sample) * 4 < 100L * 1024 * 1024);
    }

    @Test
    public void picturesWithinTheBudgetAreLeftAlone() {
        Assert.assertEquals(1, ImageBudget.fit(1080, 2400, 1, BACKGROUND));
        Assert.assertEquals(4, ImageBudget.fit(1080, 2400, 4, BACKGROUND));
        Assert.assertEquals(1, ImageBudget.fit(-1, -1, 0, BACKGROUND)); // undecodable header
    }

    @Test
    public void randomSizesNeverExceedTheBudget() {
        final Random random = new Random(11);
        for (int i = 0; i < 500_000; i++) {
            final int w = 1 + random.nextInt(i % 2 == 0 ? 8192 : Integer.MAX_VALUE - 1);
            final int h = 1 + random.nextInt(i % 3 == 0 ? 8192 : Integer.MAX_VALUE - 1);
            final int start = 1 << random.nextInt(4);
            final int sample = ImageBudget.fit(w, h, start, BACKGROUND);
            Assert.assertTrue(w + "x" + h, sample >= start && Integer.bitCount(sample) == 1);
            Assert.assertTrue(w + "x" + h, pixels(w, h, sample) <= BACKGROUND);
        }
    }

    private static long pixels(final int width, final int height, final int sample) {
        return ((long) width + sample - 1) / sample * (((long) height + sample - 1) / sample);
    }
}
