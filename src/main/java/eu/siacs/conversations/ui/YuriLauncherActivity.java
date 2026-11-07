package eu.siacs.conversations.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.common.base.Strings;
import de.gultsch.common.MiniUri;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.utils.XmppUriLauncher;
import eu.siacs.conversations.xmpp.manager.OAuthManager;

public class YuriLauncherActivity extends AppCompatActivity {

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final var intent = getIntent();
        final var data = intent == null ? null : intent.getData();
        if (data == null) {
            new Handler(Looper.getMainLooper()).post(this::finish);
            return;
        }
        final MiniUri uri;
        try {
            uri = MiniUri.tryInternalParse(data.toString());
        } catch (final IllegalArgumentException e) {
            Log.d(Config.LOGTAG, "could not parse mini uri", e);
            Toast.makeText(this, R.string.invalid_jid, Toast.LENGTH_LONG).show();
            new Handler(Looper.getMainLooper()).post(this::finish);
            return;
        }
        if (uri instanceof MiniUri.Application application
                && OAuthManager.OAUTH_REDIRECT_HOST.equals(application.getAuthority())) {
            final var state = application.getParameter("state");
            final var account = application.getAccount();
            if (Strings.isNullOrEmpty(state) || account == null) {
                Toast.makeText(this, R.string.could_not_process_oauth_redirect, Toast.LENGTH_LONG)
                        .show();
                new Handler(Looper.getMainLooper()).post(this::finish);
                return;
            }
            final var accounts =
                    DatabaseBackend.AccountWithOptions.getAddresses(
                            Conversations.getInstance(this).getAccounts());
            if (accounts.contains(account)) {
                final var editAccountIntent = new Intent(this, EditAccountActivity.class);
                editAccountIntent.putExtra("jid", account.asBareJid().toString());
                editAccountIntent.setData(application.asUri());
                editAccountIntent.addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(editAccountIntent);
            } else {
                Toast.makeText(this, R.string.could_not_process_oauth_redirect, Toast.LENGTH_LONG)
                        .show();
            }
            new Handler(Looper.getMainLooper()).post(this::finish);
            return;
        }
        final MiniUri.Xmpp xmpp;
        if (uri instanceof MiniUri.Xmpp x) {
            xmpp = x;
        } else if (uri instanceof MiniUri.Transformable t
                && t.transform() instanceof MiniUri.Xmpp x) {
            xmpp = x;
        } else {
            Log.d(Config.LOGTAG, "mini uri is of unknown type: " + uri.getClass().getSimpleName());
            Toast.makeText(this, R.string.invalid_jid, Toast.LENGTH_LONG).show();
            new Handler(Looper.getMainLooper()).post(this::finish);
            return;
        }
        final var launcher = new XmppUriLauncher(this);
        launcher.launch(xmpp);
        new Handler(Looper.getMainLooper()).post(this::finish);
    }
}
