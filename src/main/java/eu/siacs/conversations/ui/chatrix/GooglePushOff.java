package eu.siacs.conversations.ui.chatrix;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.manager.PushNotificationManager;
import im.conversations.android.xmpp.IqErrorException;

/**
 * Chatrix.One: Google push is always off (AppSettings.isGooglePush). 2.21.4-cx1.1 still showed
 * the switch; whoever turned it on has an XEP-0357 registration on their own server that sends
 * wake-ups to the Conversations push server. Once per account, after login, we ask the own
 * server to stop that ("disable" without a node = every node of that push server).
 *
 * <p>Only the user's own server is contacted, never the push server itself: telling it to
 * unregister would send it the device ID again, also for people who never registered.
 *
 * <p>Known side effect, accepted (ultrareview 2026-11-26): without a node the server also drops
 * the registrations of the account's other devices with that push server, e.g. Conversations
 * with Google push on another phone. That phone gets no push wake-ups until its next login, when
 * it registers again (upstream does that on every bind). Our own node is not known: upstream
 * keeps it only in memory, and asking the push server for it would send it the device ID. Sent
 * once per account, and only to people who switched Google push on in 2.21.4-cx1.1.
 */
public final class GooglePushOff {

    private static final String DONE_PREFIX = "chatrix_google_push_off_";

    private GooglePushOff() {}

    public static void onBind(
            final Context context, final XmppConnection connection, final Account account) {
        final var prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!needed(prefs, account.getUuid())) {
            return;
        }
        final var pushManager = connection.getManager(PushNotificationManager.class);
        if (!pushManager.hasFeature()) {
            markDone(prefs, account.getUuid()); // no push on this server: nothing was registered
            return;
        }
        final Jid appServer = Jid.of(context.getString(R.string.app_server));
        Futures.addCallback(
                pushManager.disable(appServer, null),
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(final Void result) {
                        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": google push off");
                        markDone(prefs, account.getUuid());
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable t) {
                        if (t instanceof IqErrorException) {
                            // the server answered: there is nothing more we can ask of it
                            markDone(prefs, account.getUuid());
                        }
                        // timeout or lost connection: try again after the next login
                        Log.d(Config.LOGTAG, "could not turn off google push", t);
                    }
                },
                MoreExecutors.directExecutor());
    }

    /** Only for people who once switched Google push on, and once per account. */
    static boolean needed(final SharedPreferences prefs, final String accountUuid) {
        return prefs.getBoolean(AppSettings.GOOGLE_PUSH, false)
                && !prefs.getBoolean(DONE_PREFIX + accountUuid, false);
    }

    static void markDone(final SharedPreferences prefs, final String accountUuid) {
        prefs.edit().putBoolean(DONE_PREFIX + accountUuid, true).apply();
    }
}
