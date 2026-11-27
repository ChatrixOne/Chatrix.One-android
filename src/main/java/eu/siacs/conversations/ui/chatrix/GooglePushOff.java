package eu.siacs.conversations.ui.chatrix;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.manager.PushNotificationManager;
import im.conversations.android.xmpp.IqErrorException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.List;

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
 * once per address, only to people who switched Google push on in 2.21.4-cx1.1, and only for
 * the addresses that were on the phone at the first check ({@link GooglePushGate}): an account
 * added later never gets it.
 *
 * <p>The playstore flavor's default for google_push is true, but that flavor was never
 * published: only free builds are, where the default is false (ultrareview 2026-11-27, nit).
 */
public final class GooglePushOff {

    private GooglePushOff() {}

    public static void onBind(
            final Context context,
            final XmppConnection connection,
            final Account account,
            final Collection<Account> accounts) {
        final var prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String jid = account.getJid().asBareJid().toString();
        if (!GooglePushGate.needed(prefs, account.getUuid(), jid, () -> addresses(accounts))) {
            return;
        }
        final var pushManager = connection.getManager(PushNotificationManager.class);
        if (!pushManager.hasFeature()) {
            // no push on this server: nothing was registered
            GooglePushGate.markDone(prefs, account.getUuid(), jid);
            return;
        }
        final Jid appServer = Jid.of(context.getString(R.string.app_server));
        Futures.addCallback(
                pushManager.disable(appServer, null),
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(final Void result) {
                        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": google push off");
                        GooglePushGate.markDone(prefs, account.getUuid(), jid);
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable t) {
                        if (t instanceof IqErrorException) {
                            // the server answered: there is nothing more we can ask of it
                            GooglePushGate.markDone(prefs, account.getUuid(), jid);
                        }
                        // timeout or lost connection: try again after the next login
                        Log.d(Config.LOGTAG, "could not turn off google push", t);
                    }
                },
                MoreExecutors.directExecutor());
    }

    /**
     * Bare JIDs of all accounts, or null if the list kept changing while read (upstream's plain
     * ArrayList): then the gate waits for the next login rather than break this one.
     */
    @Nullable
    private static List<String> addresses(final Collection<Account> accounts) {
        for (int attempt = 0; ; attempt++) {
            try {
                final List<String> addresses = new ArrayList<>();
                for (final Account a : accounts) {
                    addresses.add(a.getJid().asBareJid().toString());
                }
                return addresses;
            } catch (final ConcurrentModificationException e) {
                if (attempt == 2) {
                    return null;
                }
            }
        }
    }
}
