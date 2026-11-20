package eu.siacs.conversations.ui.chatrix;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

/** Chatrix.One: reading version.json for the update check (org.json needs Robolectric). */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class UpdateCheckWorkerTest {

    @Test
    public void readsOurVersionJson() throws Exception {
        // the live file on chatrix.eu, with the url field the app does not use
        final var latest =
                UpdateCheckWorker.parse(
                        in(
                                "{\"versionCode\": 4218701, \"versionName\": \"2.21.4-cx1.1\","
                                        + " \"url\": \"https://github.com/ChatrixOne/"
                                        + "Chatrix.One-android/releases\"}"));
        Assert.assertNotNull(latest);
        Assert.assertEquals(4218701, latest.code());
        Assert.assertEquals("2.21.4-cx1.1", latest.name());
    }

    @Test
    public void acceptsOurOtherVersionNames() throws Exception {
        for (final String name : new String[] {"2.21.0-cx1-5", "2.21.2-cx1", "2.22.0"}) {
            final var latest = UpdateCheckWorker.parse(in(json(name)));
            Assert.assertNotNull(name, latest);
            Assert.assertEquals(name, latest.name());
        }
    }

    /** The name ends up in the notification: anything else gets the generic title. */
    @Test
    public void dropsNamesThatAreNotVersions() throws Exception {
        for (final String name :
                new String[] {
                    "",
                    "Security alert! Call +1 555 0100",
                    "2.21.4-cx1.1 now",
                    "2.21.4-" + "x".repeat(50),
                    "<b>2.22</b>"
                }) {
            final var latest = UpdateCheckWorker.parse(in(json(name)));
            Assert.assertNotNull(name, latest);
            Assert.assertEquals(4218799, latest.code());
            Assert.assertNull(name, latest.name());
        }
    }

    @Test
    public void ignoresAnAnswerLargerThan4Kb() throws Exception {
        Assert.assertNull(UpdateCheckWorker.parse(in(json("2.22.0") + " ".repeat(5000))));
    }

    /** Stops reading after 4 KB, however much the server sends (found by removing the limit). */
    @Test(timeout = 10_000)
    public void neverReadsAnEndlessAnswer() throws Exception {
        final InputStream endless =
                new InputStream() {
                    @Override
                    public int read() {
                        return ' ';
                    }
                };
        Assert.assertNull(UpdateCheckWorker.parse(endless));
    }

    @Test(expected = JSONException.class)
    public void rejectsWhatIsNotJson() throws Exception {
        UpdateCheckWorker.parse(in("<html>not json</html>"));
    }

    private static String json(final String name) {
        return "{\"versionCode\": 4218799, \"versionName\": \"" + name + "\"}";
    }

    private static InputStream in(final String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
