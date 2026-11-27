package eu.siacs.conversations.ui.chatrix;

import android.text.SpannableStringBuilder;
import android.view.View;
import android.widget.TextView;
import de.gultsch.common.Linkify;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.ui.text.FixedURLSpan;
import eu.siacs.conversations.ui.widget.ClickableMovementMethod;
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

    /**
     * The text as MessageAdapter.displayTextMessage shows it: trimmed, cut to the display limit,
     * linkified, and set on the bubble's TextView.
     */
    private static CharSequence shown(final String rawBody) {
        final String trimmed = rawBody.trim();
        final SpannableStringBuilder body;
        if (trimmed.length() > Config.MAX_DISPLAY_MESSAGE_CHARS) {
            body = new SpannableStringBuilder(trimmed, 0, Config.MAX_DISPLAY_MESSAGE_CHARS);
            body.append("…");
        } else {
            body = new SpannableStringBuilder(trimmed);
        }
        Linkify.addLinks(body);
        FixedURLSpan.fix(body);
        final TextView view = new TextView(RuntimeEnvironment.getApplication());
        view.setText(body);
        view.setMovementMethod(ClickableMovementMethod.getInstance());
        return view.getText();
    }

    @Test
    public void aLinkPastTheShownTextGetsNoCard() {
        final String filler = "word ".repeat(Config.MAX_DISPLAY_MESSAGE_CHARS / 5 + 10);
        Assert.assertNull(LinkPreviews.firstShownLink(shown(filler + "https://example.org/")));
        final var early = LinkPreviews.firstShownLink(shown("https://example.org/ " + filler));
        Assert.assertNotNull("a shown link still counts", early);
        Assert.assertFalse(early.only());
    }

    @Test
    public void theFirstHttpsLinkWins() {
        final var link =
                LinkPreviews.firstShownLink(
                        shown(
                                "see http://plain.example/ and xmpp:someone@example.org"
                                        + " then https://first.example/a?b=c and"
                                        + " https://second.example/"));
        Assert.assertNotNull(link);
        Assert.assertEquals("https://first.example/a?b=c", link.url());
        Assert.assertFalse(link.only());
    }

    @Test
    public void aMessageThatIsOnlyTheLink() {
        final var link = LinkPreviews.firstShownLink(shown("  https://example.org/page  "));
        Assert.assertNotNull(link);
        Assert.assertTrue(link.only());
        Assert.assertEquals("https://example.org/page", link.url());
    }

    @Test
    public void aLinkAfterANameIsNotTheWholeText() {
        // a /me message shows the sender's name in front
        final var link = LinkPreviews.firstShownLink(shown("Alice https://example.org/"));
        Assert.assertNotNull(link);
        Assert.assertFalse(link.only());
    }

    @Test
    public void aLocalFirstLinkMeansNoCard() {
        Assert.assertNull(
                LinkPreviews.firstShownLink(shown("https://192.168.1.1/ https://example.org/")));
        Assert.assertNull(LinkPreviews.firstShownLink(shown("https://localhost/")));
    }

    @Test
    public void noLinkNoCard() {
        Assert.assertNull(LinkPreviews.firstShownLink(shown("just words, no link")));
        Assert.assertNull(LinkPreviews.firstShownLink(shown("http://plain.example/")));
        Assert.assertNull(
                "plain text has no spans", LinkPreviews.firstShownLink("https://x.org/"));
        Assert.assertNull(LinkPreviews.firstShownLink(""));
        Assert.assertNull(LinkPreviews.firstShownLink(null));
    }

    /** bind() reads the links MessageAdapter made, so it has to run after them. */
    @Test
    public void bindReadsTheShownText() throws IOException {
        final String adapter =
                read("src/main/java/eu/siacs/conversations/ui/adapter/MessageAdapter.java");
        final int start = adapter.indexOf("private void displayTextMessage(");
        final int end = adapter.indexOf("\n    }\n", start);
        Assert.assertTrue("displayTextMessage not found", start >= 0 && end > start);
        final String display = adapter.substring(start, end);
        final int linkify = display.indexOf("Linkify.addLinks(body);");
        final int set = display.indexOf("viewHolder.messageBody().setText(body);");
        final int bind = display.indexOf("LinkPreviews.bind(");
        Assert.assertTrue("links are made", linkify >= 0);
        Assert.assertTrue("then shown", set > linkify);
        Assert.assertTrue("then read by bind", bind > set);
        Assert.assertEquals(
                "bind gets the bubble's text", bind, display.lastIndexOf("LinkPreviews.bind("));
        Assert.assertTrue(display.substring(bind).contains("viewHolder.messageBody(),"));
        final String previews =
                read("src/main/java/eu/siacs/conversations/ui/chatrix/LinkPreviews.java");
        Assert.assertFalse("no second scan", previews.contains("Linkify.getLinks("));
        Assert.assertFalse("not the stored body", previews.contains("message.getBody()"));
        Assert.assertTrue(previews.contains("eligibleLink(message, body.getText())"));
    }

    private static String read(final String path) throws IOException {
        final File file = new File(System.getProperty("user.dir"), path);
        Assert.assertTrue("source not found at " + file, file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** bind() claims the card and its callback checks the token, not the link. */
    @Test
    public void bindUsesTheToken() throws IOException {
        final String source =
                read("src/main/java/eu/siacs/conversations/ui/chatrix/LinkPreviews.java");
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
