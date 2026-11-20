package eu.siacs.conversations.ui.chatrix;

import org.junit.Assert;
import org.junit.Test;

/** Chatrix.One: which addresses a chatrixone://login link may fill in. */
public class LoginLinkTest {

    @Test
    public void acceptsChatrixOneAddresses() {
        Assert.assertEquals("alice@chatrix.one", LoginLink.jid("alice@chatrix.one"));
        Assert.assertEquals("Alice.B@chatrix.one", LoginLink.jid("Alice.B@Chatrix.ONE"));
    }

    @Test
    public void refusesEverythingElse() {
        for (final String jid :
                new String[] {
                    "alice@chatrix-one.com",
                    "alice@chatrix.one.evil.example",
                    "alice@evil.example",
                    "alice@chatrix.оne", // Cyrillic о
                    "alice@chatrix.one/resource",
                    "@chatrix.one",
                    "alice",
                    "a@b@chatrix.one",
                    "al ice@chatrix.one",
                    "x".repeat(300) + "@chatrix.one",
                    ""
                }) {
            Assert.assertNull(jid, LoginLink.jid(jid));
        }
        Assert.assertNull(LoginLink.jid(null));
    }
}
