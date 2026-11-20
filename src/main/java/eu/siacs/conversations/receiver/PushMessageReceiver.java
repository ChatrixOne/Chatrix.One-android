package eu.siacs.conversations.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.common.hash.Hashing;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.services.PushManagementService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.PhoneHelper;
import eu.siacs.conversations.xmpp.Jid;

public class PushMessageReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null) {
            Log.e(Config.LOGTAG, "PushMessageReceiver got woken up with null intent");
            return;
        }
        final var extras = intent.getExtras();
        if (extras == null) {
            Log.d(Config.LOGTAG, "PushMessageReceiver got woken up without extras");
            return;
        }
        switch (Strings.nullToEmpty(intent.getAction())) {
            case PushManagementService.ACTION_REGISTRATION -> onNewToken(context, extras);
            case PushManagementService.ACTION_RECEIVE -> onMessageReceived(context, extras);
        }
    }

    private void onMessageReceived(final Context context, final Bundle extras) {
        final var hash = extras.getString("account");
        if (Strings.isNullOrEmpty(hash)) {
            Log.d(Config.LOGTAG, "PushMessageReceiver received push w/o account hash");
            return;
        }
        final var androidId = PhoneHelper.getAndroidId(context);
        if (Strings.isNullOrEmpty(androidId)) {
            Log.d(Config.LOGTAG, "PushMessageReceiver could not access android id");
            return;
        }
        if (!Conversations.getInstance(context.getApplicationContext()).hasEnabledAccount()) {
            Log.d(
                    Config.LOGTAG,
                    "PushMessageReceiver ignored message because no accounts are enabled");
            return;
        }
        final var accounts =
                DatabaseBackend.AccountWithOptions.getAddresses(
                        Conversations.getInstance(context).getAccounts());
        final var account = Iterables.tryFind(accounts, a -> hash(a, androidId).equals(hash));
        if (account.isPresent()) {
            Log.d(
                    Config.LOGTAG,
                    "PushMessageReceiver received push notification. waking up service");
            final Intent intent = new Intent(context, XmppConnectionService.class);
            intent.setAction(XmppConnectionService.ACTION_FCM_MESSAGE_RECEIVED);
            intent.putExtra("account", account.get().toString());
            Compatibility.startService(context, intent);
        } else {
            Log.d(Config.LOGTAG, "PushMessageReceiver could not identify account");
        }
    }

    private static String hash(final Jid jid, final String androidId) {
        return Hashing.sha1()
                .hashString(jid.toString() + "\00" + androidId, Charsets.UTF_8)
                .toString();
    }

    private void onNewToken(final Context context, final Bundle extras) {
        final var registrationId = extras == null ? null : extras.getString("registration_id");
        Log.d(Config.LOGTAG, "onNewToken(" + registrationId + ")");

        if (!Conversations.getInstance(context.getApplicationContext()).hasEnabledAccount()) {
            Log.d(
                    Config.LOGTAG,
                    "PushMessageReceiver ignored new token because no accounts are enabled");
            return;
        }
        final Intent intent = new Intent(context, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_FCM_TOKEN_REFRESH);
        Compatibility.startService(context, intent);
    }
}
