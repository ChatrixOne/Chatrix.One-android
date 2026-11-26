package eu.siacs.conversations.ui.chatrix;

import im.conversations.android.xml.XmlElementReader;
import im.conversations.android.xmpp.model.muc.user.Item;
import im.conversations.android.xmpp.model.muc.user.MucUser;
import im.conversations.android.xmpp.model.stanza.Message;
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
 * Chatrix.One (security review S7-2, fixed upstream in 2.21.5): the sender of an archived group
 * message (MAM: history and the catch-up after being offline).
 *
 * <p>A member can put their own {@code <x xmlns='http://jabber.org/protocol/muc#user'><item
 * jid='...'/></x>} into a group message, naming any real address, also ours. ejabberd removes it
 * before archiving. Prosody keeps it and, in non-anonymous rooms, appends its own element after it.
 * MultiUserChatManager.getMucUser trusts the element only when the message carries exactly one
 * (getOnlyExtension); otherwise the sender is looked up by occupant id. This test pins both parts,
 * so an upstream change back to getExtension fails here on the next merge.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ArchivedGroupSenderTest {

    private static final String FORGED =
            "<x xmlns='http://jabber.org/protocol/muc#user'>"
                    + "<item affiliation='owner' role='moderator' jid='me@chatrix.one'/></x>";
    private static final String REAL =
            "<x xmlns='http://jabber.org/protocol/muc#user'>"
                    + "<item affiliation='member' role='participant' jid='mallory@example.org'/></x>";

    private static Message archived(final String extensions) throws IOException {
        return XmlElementReader.read(
                "<message xmlns='jabber:client' from='room@muc.example.org/mallory'"
                        + " type='groupchat' id='m1'><body>hi</body>"
                        + extensions
                        + "</message>",
                Message.class);
    }

    /** The same expression as in MultiUserChatManager.getMucUser for archived messages. */
    private static Item trustedItem(final Message message) {
        final var mucUser = message.getOnlyExtension(MucUser.class);
        return mucUser == null ? null : mucUser.getItem();
    }

    @Test
    public void oneElementIsTrusted() throws IOException {
        // ejabberd (strips the member's element, adds the real one) and Prosody without forgery
        final var item = trustedItem(archived(REAL));
        Assert.assertNotNull(item);
        Assert.assertEquals("mallory@example.org", item.getAttribute("jid"));
    }

    @Test
    public void forgedElementNextToTheRealOneIsNotTrusted() throws IOException {
        // Prosody, non-anonymous room: keeps the member's element and appends the real one
        Assert.assertNull(trustedItem(archived(FORGED + REAL)));
        Assert.assertNull(trustedItem(archived(REAL + FORGED)));
    }

    @Test
    public void noElementGivesNothing() throws IOException {
        Assert.assertNull(trustedItem(archived("")));
        Assert.assertNull(trustedItem(archived("<x xmlns='http://jabber.org/protocol/muc#user'/>")));
    }

    @Test
    public void elementsOfOtherNamespacesDoNotCount() throws IOException {
        final var item =
                trustedItem(
                        archived(
                                "<x xmlns='http://jabber.org/protocol/muc#admin'>"
                                        + "<item jid='me@chatrix.one'/></x>"
                                        + REAL));
        Assert.assertNotNull(item);
        Assert.assertEquals("mallory@example.org", item.getAttribute("jid"));
    }

    @Test
    public void getMucUserUsesOnlyASingleElement() throws IOException {
        final String body = getMucUserBody();
        Assert.assertTrue(body, body.contains("message.getOnlyExtension(MucUser.class)"));
        Assert.assertFalse(body, body.contains("getExtension(MucUser.class)"));
    }

    /** The source of MultiUserChatManager.getMucUser, from its signature to the next method. */
    static String getMucUserBody() throws IOException {
        final File file =
                new File(
                        System.getProperty("user.dir"),
                        "src/main/java/eu/siacs/conversations/xmpp/manager/MultiUserChatManager.java");
        Assert.assertTrue("source not found at " + file, file.isFile());
        final String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        final int start = source.indexOf("public MucOptions.User getMucUser(");
        Assert.assertTrue("getMucUser not found", start >= 0);
        final int end = source.indexOf("public static MucOptions.User itemToUser(", start);
        Assert.assertTrue("end of getMucUser not found", end > start);
        return source.substring(start, end);
    }
}
