package eu.siacs.conversations.ui.chatrix;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.crypto.OmemoSetting;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xmpp.Jid;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.ConscryptMode;

/** Chatrix.One: what our edits in upstream files must keep doing (security review step 5). */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ChatrixUpstreamSettingsTest {

    private Context context;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        OmemoSetting.load(context);
    }

    @After
    public void tearDown() {
        prefs.edit().clear().commit();
        OmemoSetting.load(context); // OmemoSetting is global: back to the default
    }

    /** S5-1: admin@chatrix.one is a person, so chats with it get OMEMO like any other. */
    @Test
    public void chatWithTheAdminIsEncryptedByDefault() {
        Assert.assertEquals(
                Message.ENCRYPTION_AXOLOTL, chatWith(Config.BUG_REPORTS).getNextEncryption());
        Assert.assertEquals(
                Message.ENCRYPTION_AXOLOTL,
                chatWith(Jid.of("friend@chatrix.one")).getNextEncryption());
    }

    @Test
    public void chatWithTheAdminIsEncryptedWithOmemoAlways() {
        prefs.edit().putString(AppSettings.OMEMO, "always").commit();
        OmemoSetting.load(context);
        Assert.assertTrue(OmemoSetting.isAlways());
        Assert.assertEquals(
                Message.ENCRYPTION_AXOLOTL, chatWith(Config.BUG_REPORTS).getNextEncryption());
    }

    /** S5-2: TLS 1.3 + channel binding stay required for our server and for upstream's. */
    @Test
    public void strictTlsForChatrixOneAndConversationsIm() {
        Assert.assertTrue(AppSettings.SECURE_DOMAINS.contains(Jid.ofDomain("chatrix.one")));
        Assert.assertTrue(AppSettings.SECURE_DOMAINS.contains(Jid.ofDomain("conversations.im")));
    }

    /** S5-3: Google push stays off, even for someone who switched it on in 2.21.4-cx1.1. */
    @Test
    public void googlePushIsAlwaysOff() {
        prefs.edit().putBoolean(AppSettings.GOOGLE_PUSH, true).commit();
        Assert.assertFalse(new AppSettings(context).isGooglePush());
    }

    @Test
    public void googlePushIsTurnedOffOncePerAccountAndOnlyIfItWasOn() {
        Assert.assertFalse(GooglePushOff.needed(prefs, "account-1"));
        prefs.edit().putBoolean(AppSettings.GOOGLE_PUSH, true).commit();
        Assert.assertTrue(GooglePushOff.needed(prefs, "account-1"));
        GooglePushOff.markDone(prefs, "account-1");
        Assert.assertFalse(GooglePushOff.needed(prefs, "account-1"));
        Assert.assertTrue(GooglePushOff.needed(prefs, "account-2"));
    }

    /** A one-on-one chat without the XMPP service: the contact is made here, not by the roster. */
    private static Conversation chatWith(final Jid address) {
        final Account account = new Account(Jid.of("user@chatrix.one"), "secret");
        final Contact contact = new Contact(address);
        contact.setAccount(account);
        return new Conversation("chat", account, address, Conversational.MODE_SINGLE) {
            @Override
            public Contact getContact() {
                return contact;
            }
        };
    }
}
