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
import java.lang.reflect.Field;
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
    private boolean savedAlways;
    private int savedEncryption;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        savedAlways = OmemoSetting.isAlways();
        savedEncryption = OmemoSetting.getEncryption();
        setOmemo(false, Message.ENCRYPTION_AXOLOTL); // the default: "on by default"
    }

    @After
    public void tearDown() throws Exception {
        prefs.edit().clear().commit();
        setOmemo(savedAlways, savedEncryption); // OmemoSetting is global
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
    public void chatWithTheAdminIsEncryptedWithOmemoAlways() throws Exception {
        setOmemo(true, Message.ENCRYPTION_AXOLOTL); // what OmemoSetting.load() sets for "always"
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
        // with the real SharedPreferences; the rules themselves: GooglePushGateTest
        final var phone = java.util.List.of("a@example.org", "b@example.org");
        Assert.assertFalse(GooglePushGate.needed(prefs, "account-1", "a@example.org", () -> phone));
        prefs.edit().putBoolean(AppSettings.GOOGLE_PUSH, true).commit();
        Assert.assertTrue(GooglePushGate.needed(prefs, "account-1", "a@example.org", () -> phone));
        GooglePushGate.markDone(prefs, "account-1", "a@example.org");
        Assert.assertFalse(GooglePushGate.needed(prefs, "account-1", "a@example.org", () -> phone));
        Assert.assertTrue(GooglePushGate.needed(prefs, "account-2", "b@example.org", () -> phone));
        Assert.assertFalse(GooglePushGate.needed(prefs, "account-3", "c@example.org", () -> phone));
    }

    /**
     * OmemoSetting.load() reads its default from the app's resources, which these unit tests do
     * not have (Resources$NotFoundException), so the two fields it sets are set directly.
     */
    private static void setOmemo(final boolean always, final int encryption) throws Exception {
        final Field alwaysField = OmemoSetting.class.getDeclaredField("always");
        alwaysField.setAccessible(true);
        alwaysField.setBoolean(null, always);
        final Field encryptionField = OmemoSetting.class.getDeclaredField("encryption");
        encryptionField.setAccessible(true);
        encryptionField.setInt(null, encryption);
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
