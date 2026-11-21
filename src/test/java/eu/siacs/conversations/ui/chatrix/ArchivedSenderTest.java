package eu.siacs.conversations.ui.chatrix;

import im.conversations.android.xml.XmlElementReader;
import im.conversations.android.xmpp.model.stanza.Message;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

/** Chatrix.One (security review S7-2): sender details of archived group messages. */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ArchivedSenderTest {

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

    @Test
    public void oneItemIsTrusted() throws IOException {
        // ejabberd (strips the member's element, adds the real one) and Prosody without forgery
        final var item = ArchivedSender.onlyItem(archived(REAL));
        Assert.assertNotNull(item);
        Assert.assertEquals("mallory@example.org", item.getAttribute("jid"));
    }

    @Test
    public void forgedElementBeforeTheRealOneIsNotTrusted() throws IOException {
        // Prosody, non-anonymous room: keeps the member's element and appends the real one
        Assert.assertNull(ArchivedSender.onlyItem(archived(FORGED + REAL)));
        Assert.assertNull(ArchivedSender.onlyItem(archived(REAL + FORGED)));
    }

    @Test
    public void twoItemsInOneElementAreNotTrusted() throws IOException {
        Assert.assertNull(
                ArchivedSender.onlyItem(
                        archived(
                                "<x xmlns='http://jabber.org/protocol/muc#user'>"
                                        + "<item jid='me@chatrix.one'/>"
                                        + "<item jid='mallory@example.org'/></x>")));
    }

    @Test
    public void noItemGivesNothing() throws IOException {
        Assert.assertNull(ArchivedSender.onlyItem(archived("")));
        Assert.assertNull(
                ArchivedSender.onlyItem(
                        archived("<x xmlns='http://jabber.org/protocol/muc#user'/>")));
    }

    @Test
    public void elementsOfOtherNamespacesDoNotCount() throws IOException {
        final var item =
                ArchivedSender.onlyItem(
                        archived(
                                "<x xmlns='http://jabber.org/protocol/muc#admin'>"
                                        + "<item jid='me@chatrix.one'/></x>"
                                        + REAL));
        Assert.assertNotNull(item);
        Assert.assertEquals("mallory@example.org", item.getAttribute("jid"));
    }
}
