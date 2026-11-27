package eu.siacs.conversations.ui.chatrix;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

/**
 * Chatrix.One: only a waveform of the whole file is cached for good (ultrareview 2026-11-26: one
 * cut short by the time limit was cached, and a long voice message looked silent at the end).
 * Decoding itself needs Android's media decoder and is not run here.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class WaveformsTest {

    @Test
    public void theDecoderReportedTheEnd() {
        Assert.assertTrue(Waveforms.finished(true, true, false));
        Assert.assertTrue("passed the declared duration", Waveforms.finished(true, false, false));
    }

    @Test
    public void quietAfterTheLastInputIsTheEnd() {
        // some decoders never send the end flag: the idle limit ends the loop
        Assert.assertTrue(Waveforms.finished(false, true, false));
    }

    @Test
    public void cutShortIsNotFinished() {
        Assert.assertFalse("time limit", Waveforms.finished(false, true, true));
        Assert.assertFalse("time limit before all input", Waveforms.finished(false, false, true));
        Assert.assertFalse("stalled before all input", Waveforms.finished(false, false, false));
    }

    /** The disk cache is written only for a finished waveform. */
    @Test
    public void onlyAFinishedWaveformIsCached() throws IOException {
        final File file =
                new File(
                        System.getProperty("user.dir"),
                        "src/main/java/eu/siacs/conversations/ui/chatrix/Waveforms.java");
        Assert.assertTrue("source not found at " + file, file.isFile());
        final String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        final int write = source.indexOf("writeDiskCache(cacheDir, key, levels)");
        Assert.assertTrue("disk cache write not found", write >= 0);
        Assert.assertEquals(
                "one write only", write, source.lastIndexOf("writeDiskCache(cacheDir, key, levels)"));
        final int guard = source.lastIndexOf("if (decoded.finished())", write);
        Assert.assertTrue("write not guarded by finished()", guard >= 0 && write - guard < 120);
        Assert.assertTrue(
                "decode reports finished()",
                source.contains("return new Decoded(peaks, finished(outputDone, inputDone, outOfTime))"));
    }
}
