package eu.siacs.conversations.ui.chatrix;

import android.view.View;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.ConscryptMode;

/**
 * Chatrix.One: a preview that loads late only reaches the row if it still shows the same message
 * (ultrareview 2026-11-26: with the same link in the next message on a reused row, the old
 * callback hid the new message's text).
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class LinkPreviewsTest {

    private static View card() {
        return new View(RuntimeEnvironment.getApplication());
    }

    @Test
    public void aReusedRowDropsTheOldCallback() {
        final View card = card();
        final Object first = LinkPreviews.claim(card); // message A, link https://example.org/
        final Object second = LinkPreviews.claim(card); // message B on the same row, same link
        Assert.assertFalse(LinkPreviews.stillShows(card, first));
        Assert.assertTrue(LinkPreviews.stillShows(card, second));
    }

    @Test
    public void aHiddenCardTakesNoPreview() {
        final View card = card();
        final Object token = LinkPreviews.claim(card);
        LinkPreviews.hide(card); // the row now shows a picture or file
        Assert.assertFalse(LinkPreviews.stillShows(card, token));
        Assert.assertEquals(View.GONE, card.getVisibility());
    }

    @Test
    public void tokensOfOtherRowsDoNotCount() {
        final View one = card();
        final View two = card();
        final Object a = LinkPreviews.claim(one);
        final Object b = LinkPreviews.claim(two);
        Assert.assertTrue(LinkPreviews.stillShows(one, a));
        Assert.assertFalse(LinkPreviews.stillShows(one, b));
        Assert.assertFalse(LinkPreviews.stillShows(two, a));
    }

    /** bind() claims the card and its callback checks the token, not the link. */
    @Test
    public void bindUsesTheToken() throws IOException {
        final File file =
                new File(
                        System.getProperty("user.dir"),
                        "src/main/java/eu/siacs/conversations/ui/chatrix/LinkPreviews.java");
        Assert.assertTrue("source not found at " + file, file.isFile());
        final String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        final int start = source.indexOf("public static void bind(");
        final int end = source.indexOf("\n    }\n", start);
        Assert.assertTrue("bind not found", start >= 0 && end > start);
        final String bind = source.substring(start, end);
        Assert.assertTrue(bind.contains("final Object token = claim(card);"));
        Assert.assertTrue(bind.contains("stillShows(card, token)"));
        Assert.assertFalse("the link alone says nothing", bind.contains("card.getTag()"));
        Assert.assertFalse(bind.contains("card.setTag("));
    }
}
