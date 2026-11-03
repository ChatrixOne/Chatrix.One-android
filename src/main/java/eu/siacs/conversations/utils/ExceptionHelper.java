package eu.siacs.conversations.utils;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.base.Charsets;
import com.google.common.io.CharSink;
import com.google.common.io.Files;

import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.XmppActivity;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Locale;

public class ExceptionHelper {

    private static final String FILENAME = "stacktrace.txt";

    public static void init(final Context context) {
        if (Thread.getDefaultUncaughtExceptionHandler() instanceof ExceptionHandler) {
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler(new ExceptionHandler(context));
    }

    public static boolean checkForCrash(final XmppActivity activity) {
        final XmppConnectionService service =
                activity == null ? null : activity.xmppConnectionService;
        if (service == null) {
            return false;
        }
        final AppSettings appSettings = new AppSettings(activity);
        if (!appSettings.isSendCrashReports() || Config.BUG_REPORTS == null) {
            return false;
        }
        // Chatrix.One: without an account the report can still be sent by email
        final Account account = AccountUtils.getFirstEnabled(service);
        final var file = new File(activity.getCacheDir(), FILENAME);
        if (!file.exists()) {
            return false;
        }
        final String report;
        try {
            report = Files.asCharSource(file, Charsets.UTF_8).read();
        } catch (final IOException e) {
            return false;
        }
        if (file.delete()) {
            Log.d(Config.LOGTAG, "deleted crash report file");
        }
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
        builder.setTitle(
                activity.getString(
                        R.string.crash_report_title, activity.getString(R.string.app_name)));
        // Chatrix.One: say exactly what would be sent and show it before anything happens
        builder.setMessage(
                activity.getString(R.string.chatrix_crash_report_message, Config.BUG_REPORTS_EMAIL));
        builder.setView(reportView(activity, report));
        if (account != null) {
            builder.setPositiveButton(
                    activity.getText(R.string.send_now),
                    (dialog, which) -> {
                        Log.d(
                                Config.LOGTAG,
                                "using account="
                                        + account.getJid().asBareJid()
                                        + " to send in stack trace");
                        Conversation conversation =
                                service.findOrCreateConversation(
                                        account, Config.BUG_REPORTS, false, true);
                        Message message = new Message(conversation, report, Message.ENCRYPTION_NONE);
                        service.sendMessage(message);
                    });
        }
        builder.setNeutralButton(
                R.string.chatrix_crash_send_email,
                (dialog, which) -> {
                    final Intent intent =
                            new Intent(
                                    Intent.ACTION_SENDTO,
                                    Uri.parse("mailto:" + Config.BUG_REPORTS_EMAIL));
                    intent.putExtra(
                            Intent.EXTRA_SUBJECT,
                            activity.getString(
                                    R.string.crash_report_title,
                                    activity.getString(R.string.app_name)));
                    intent.putExtra(Intent.EXTRA_TEXT, report);
                    try {
                        activity.startActivity(intent);
                    } catch (final ActivityNotFoundException e) {
                        Toast.makeText(activity, R.string.no_application_found, Toast.LENGTH_LONG)
                                .show();
                    }
                });
        builder.setNegativeButton(
                activity.getText(R.string.send_never),
                (dialog, which) -> appSettings.setSendCrashReports(false));
        builder.create().show();
        return true;
    }

    private static android.view.View reportView(final Context context, final String report) {
        final float density = context.getResources().getDisplayMetrics().density;
        final android.widget.TextView text = new android.widget.TextView(context);
        text.setText(report);
        text.setTextIsSelectable(true);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        final int pad = (int) (12 * density);
        text.setPadding(pad, pad, pad, pad);
        text.setBackgroundResource(R.drawable.background_chatrix_hint);
        final android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(text);
        final android.widget.FrameLayout frame = new android.widget.FrameLayout(context);
        final int side = (int) (24 * density);
        frame.setPadding(side, (int) (8 * density), side, 0);
        frame.addView(
                scroll,
                new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        (int) (180 * density)));
        return frame;
    }

    static void writeToStacktraceFile(final Context context, final String msg) {
        try {
            Files.asCharSink(new File(context.getCacheDir(), FILENAME), Charsets.UTF_8).write(msg);
        } catch (IOException e) {
            Log.w(Config.LOGTAG, "could not write stack trace to file", e);
        }
    }
}
