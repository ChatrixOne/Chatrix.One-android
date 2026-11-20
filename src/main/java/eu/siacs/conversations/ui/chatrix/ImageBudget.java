package eu.siacs.conversations.ui.chatrix;

/**
 * Chatrix.One: how far a picture must be shrunk while decoding so it fits a pixel budget. The
 * size in a file header can be anything (a long screenshot, or a few KB of PNG claiming
 * 383 x 1,000,000 pixels), so every decode of a picture we did not make goes through here.
 */
final class ImageBudget {

    private ImageBudget() {}

    /**
     * The smallest power-of-two sample size, at least {@code sample}, with which a picture of this
     * size decodes to no more than {@code maxPixels} pixels.
     */
    static int fit(final int width, final int height, final int sample, final long maxPixels) {
        final long w = width;
        final long h = height;
        int result = Math.max(1, sample);
        while (((w + result - 1) / result) * ((h + result - 1) / result) > maxPixels) {
            result *= 2;
        }
        return result;
    }
}
