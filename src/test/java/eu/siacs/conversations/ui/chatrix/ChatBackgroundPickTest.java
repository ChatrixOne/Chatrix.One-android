package eu.siacs.conversations.ui.chatrix;

import android.os.Bundle;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

/**
 * Chatrix.One: a picture from the photo picker still reaches its chat when Android ended the app
 * or rebuilt the screen meanwhile (ultrareview 2026-11-26: it was dropped without a word).
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ChatBackgroundPickTest {

    private static final String CHAT = UUID.randomUUID().toString();

    @Test
    public void theChatSurvivesARebuiltScreen() {
        final var before = new ChatBackgroundPick();
        before.picking(CHAT);
        final Bundle state = new Bundle();
        before.save(state);

        final var after = new ChatBackgroundPick(); // new fragment, empty fields
        after.restore(state);
        Assert.assertEquals(CHAT, after.takePicked());
        Assert.assertNull("only once", after.takePicked());
    }

    @Test
    public void nothingIsSavedWhenThePickerIsNotOpen() {
        final var pick = new ChatBackgroundPick();
        final Bundle state = new Bundle();
        pick.save(state);
        Assert.assertFalse(state.containsKey(ChatBackgroundPick.STATE_KEY));

        pick.picking(CHAT);
        pick.takePicked();
        pick.save(state);
        Assert.assertFalse("answered already", state.containsKey(ChatBackgroundPick.STATE_KEY));
    }

    @Test
    public void aSavedPictureWaitsForTheServiceOnce() {
        final var pick = new ChatBackgroundPick();
        Assert.assertNull(pick.takeWaiting());
        pick.waitForService(CHAT);
        Assert.assertEquals(CHAT, pick.takeWaiting());
        Assert.assertNull(pick.takeWaiting());
    }

    /** The uuid becomes part of a file name: nothing but a uuid is ever used. */
    @Test
    public void onlyUuidsAreKept() {
        for (final String bad :
                new String[] {
                    "",
                    "../shared_prefs/x",
                    "../../databases/history",
                    CHAT + "/x",
                    CHAT.toUpperCase(java.util.Locale.ROOT),
                    CHAT.substring(1),
                    CHAT + "\n"
                }) {
            final var pick = new ChatBackgroundPick();
            pick.picking(bad);
            Assert.assertNull(bad, pick.takePicked());
            pick.waitForService(bad);
            Assert.assertNull(bad, pick.takeWaiting());

            final Bundle state = new Bundle();
            state.putString(ChatBackgroundPick.STATE_KEY, bad);
            final var restored = new ChatBackgroundPick();
            restored.restore(state);
            Assert.assertNull(bad, restored.takePicked());
        }
        for (int i = 0; i < 1_000; i++) {
            Assert.assertTrue(ChatBackgroundPick.valid(UUID.randomUUID().toString()));
        }
    }

    /** An upstream merge must not lose the hooks in ConversationFragment. */
    @Test
    public void theFragmentSavesRestoresAndResumes() throws IOException {
        final File file =
                new File(
                        System.getProperty("user.dir"),
                        "src/main/java/eu/siacs/conversations/ui/ConversationFragment.java");
        Assert.assertTrue("source not found at " + file, file.isFile());
        final String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertIn(source, "public void onSaveInstanceState(", "chatBackgroundPick.save(outState)");
        assertIn(
                source,
                "private void processSavedInstanceState(",
                "chatBackgroundPick.restore(savedInstanceState)");
        assertIn(source, "public void onBackendConnected()", "chatBackgroundPick.takeWaiting()");
        assertIn(source, "private void onChatBackgroundPicked(", "chatBackgroundPick.takePicked()");
    }

    /** The method starting at {@code signature} (up to the next one) contains {@code call}. */
    private static void assertIn(final String source, final String signature, final String call) {
        final int start = source.indexOf(signature);
        Assert.assertTrue(signature + " not found", start >= 0);
        int end = source.indexOf("\n    @Override", start + 1);
        final int next = source.indexOf("\n    private ", start + 1);
        if (end < 0 || next >= 0 && next < end) {
            end = next;
        }
        final String body = source.substring(start, end < 0 ? source.length() : end);
        Assert.assertTrue(call + " missing in " + signature, body.contains(call));
    }
}
