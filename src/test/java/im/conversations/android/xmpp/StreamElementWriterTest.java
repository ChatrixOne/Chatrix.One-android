package im.conversations.android.xmpp;

import im.conversations.android.xml.XmlElementReader;
import im.conversations.android.xmpp.model.jabber.Body;
import im.conversations.android.xmpp.model.receipts.Request;
import im.conversations.android.xmpp.model.sm.Ack;
import im.conversations.android.xmpp.model.stanza.Message;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class StreamElementWriterTest {

    @Test
    public void emptyMessage() throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        final var streamElementWriter = new StreamElementWriter(byteArrayOutputStream);
        final var message = new Message(Message.Type.CHAT);
        streamElementWriter.write(message);
        streamElementWriter.flush();
        Assert.assertEquals(
"""
<message type="chat"/>\
""",
                byteArrayOutputStream.toString());
    }

    @Test
    public void helloWorldMessage() throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        final var streamElementWriter = new StreamElementWriter(byteArrayOutputStream);
        final var message = new Message(Message.Type.CHAT);
        message.addExtension(new Body("Hello World"));
        streamElementWriter.write(message);
        streamElementWriter.flush();
        Assert.assertEquals(
                """
                <message type="chat"><body>Hello World</body></message>\
                """,
                byteArrayOutputStream.toString());
    }

    @Test
    public void entityMessage() throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        final var streamElementWriter = new StreamElementWriter(byteArrayOutputStream);
        final var message = new Message(Message.Type.CHAT);
        message.addExtension(new Body("Jabber & XMPP"));
        streamElementWriter.write(message);
        Assert.assertEquals(0, byteArrayOutputStream.size());
        streamElementWriter.flush();
        Assert.assertEquals(
                """
                <message type="chat"><body>Jabber &amp; XMPP</body></message>\
                """,
                byteArrayOutputStream.toString());
    }

    @Test
    public void requestMessage() throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        final var streamElementWriter = new StreamElementWriter(byteArrayOutputStream);
        final var message = new Message(Message.Type.CHAT);
        message.addExtension(new Request());
        streamElementWriter.write(message);
        streamElementWriter.flush();
        Assert.assertEquals(
                """
                <message type="chat"><request xmlns="urn:xmpp:receipts"/></message>\
                """,
                byteArrayOutputStream.toString());
    }

    @Test
    public void smAck() throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        final var streamElementWriter = new StreamElementWriter(byteArrayOutputStream);
        streamElementWriter.write(new Ack());
        streamElementWriter.flush();
        Assert.assertEquals(
                """
                <a xmlns="urn:xmpp:sm:3"/>\
                """,
                byteArrayOutputStream.toString());
    }

    @Test
    public void readBackWithEntities() throws IOException {
        final var xml =
"""
<message xmlns="jabber:client"><custom xmlns="foo&quot;/&gt;"/></message>
""";
        final var message = XmlElementReader.read(xml, Message.class);
        final var asXml = StreamElementWriter.asString(message);
        Assert.assertEquals(
"""
<message><custom xmlns="foo&quot;/&gt;"/></message>\
""",
                asXml);
    }
}
