package eu.siacs.conversations.ui.chatrix;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.PackageInfoCompat;
import androidx.preference.PreferenceManager;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Chatrix.One: once a day, look at a small public version.json and show a notification when a
 * newer release exists. Nothing is downloaded or installed, and no identifiers are sent. Skipped
 * when the app was installed by an app store (F-Droid, Obtainium, …) that handles updates itself,
 * when Tor is enabled, or when the user turned it off.
 */
public class UpdateCheckWorker extends Worker {

    public static final String PREF_KEY = "chatrix_update_check";
    public static final String RELEASES_URL =
            "https://github.com/ChatrixOne/Chatrix.One-android/releases";

    private static final String PREF_LAST_NOTIFIED = "chatrix_update_last_notified";
    private static final String WORK_NAME = "chatrix_update_check";
    private static final String CHANNEL_ID = "chatrix_updates";
    private static final int NOTIFICATION_ID = 0x0c4a7;

    /** version.json is about 130 bytes: an answer this large is not ours. */
    private static final int MAX_BYTES = 4 * 1024;

    /** What our version names look like (2.21.4-cx1.1, 2.21.0-cx1-5). */
    private static final Pattern VERSION_NAME =
            Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){1,3}(?:-[A-Za-z0-9][A-Za-z0-9.-]{0,19})?");

    /** The newest release as version.json describes it; {@code name} is null if it looks wrong. */
    record Latest(long code, @Nullable String name) {}

    /** Installers that already keep the app up to date. */
    private static final Set<String> STORES =
            ImmutableSet.of(
                    "org.fdroid.fdroid",
                    "org.fdroid.basic",
                    "com.looker.droidify",
                    "com.machiav3lli.fdroid",
                    "dev.imranr.obtainium",
                    "dev.imranr.obtainium.fdroid",
                    "com.aurora.store",
                    "com.android.vending");

    public UpdateCheckWorker(
            @NonNull final Context context, @NonNull final WorkerParameters workerParams) {
        super(context, workerParams);
    }

    /** True if this build includes the update check at all (F-Droid builds can switch it off). */
    public static boolean isAvailable(final Context context) {
        return BuildConfig.CHATRIX_UPDATE_URL != null && !installedFromStore(context);
    }

    public static void schedule(final Context context) {
        if (BuildConfig.CHATRIX_UPDATE_URL == null) {
            return;
        }
        final var request =
                new PeriodicWorkRequest.Builder(UpdateCheckWorker.class, 1, TimeUnit.DAYS)
                        .setConstraints(
                                new Constraints.Builder()
                                        .setRequiredNetworkType(NetworkType.CONNECTED)
                                        .build())
                        .setInitialDelay(1, TimeUnit.HOURS)
                        .build();
        try {
            WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(
                            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
        } catch (final IllegalStateException e) {
            // WorkManager not initialised (e.g. in unit tests); try again on next start
            Log.d(Config.LOGTAG, "could not schedule chatrix update check", e);
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        final Context context = getApplicationContext();
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (BuildConfig.CHATRIX_UPDATE_URL == null
                || !prefs.getBoolean(PREF_KEY, true)
                || installedFromStore(context)
                || new AppSettings(context).isUseTor()) {
            return Result.success();
        }
        try {
            final OkHttpClient client =
                    new OkHttpClient.Builder()
                            .callTimeout(30, TimeUnit.SECONDS)
                            // version.json never moves: a redirect could only send the request
                            // somewhere else (another site, the home network)
                            .followRedirects(false)
                            .followSslRedirects(false)
                            .build();
            final Request request =
                    new Request.Builder()
                            .url(BuildConfig.CHATRIX_UPDATE_URL)
                            .header("User-Agent", "Chatrix.One")
                            .build();
            final Latest latest;
            try (final var response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return Result.success();
                }
                latest = parse(response.body().byteStream());
            }
            if (latest == null) {
                Log.d(Config.LOGTAG, "chatrix update check: version.json too large");
                return Result.success();
            }
            final long current = installedVersionCode(context);
            if (latest.code() > current && latest.code() != prefs.getLong(PREF_LAST_NOTIFIED, 0)) {
                if (notify(context, latest.name())) {
                    prefs.edit().putLong(PREF_LAST_NOTIFIED, latest.code()).apply();
                }
            }
        } catch (final IOException | JSONException | PackageManager.NameNotFoundException e) {
            Log.d(Config.LOGTAG, "chatrix update check failed: " + e.getMessage());
        } catch (final RuntimeException e) {
            if (BuildConfig.DEBUG) {
                throw e; // most likely our own bug: WorkManager reports it with the stack trace
            }
            Log.w(Config.LOGTAG, "chatrix update check failed", e);
        }
        return Result.success();
    }

    /**
     * Reads version.json; null if it is larger than {@link #MAX_BYTES}. The version name is kept
     * only if it looks like one of ours, because it ends up in the notification.
     */
    @Nullable
    static Latest parse(final InputStream in) throws IOException, JSONException {
        final byte[] bytes = ByteStreams.toByteArray(ByteStreams.limit(in, MAX_BYTES + 1));
        if (bytes.length > MAX_BYTES) {
            return null;
        }
        final JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        final String name = json.optString("versionName", "");
        return new Latest(
                json.getLong("versionCode"), VERSION_NAME.matcher(name).matches() ? name : null);
    }

    /** Base version code (per-ABI APKs add a two digit suffix to it). */
    private static long installedVersionCode(final Context context)
            throws PackageManager.NameNotFoundException {
        final var info =
                context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        return PackageInfoCompat.getLongVersionCode(info) / 100;
    }

    private static boolean installedFromStore(final Context context) {
        final String installer = installer(context);
        return installer != null && STORES.contains(installer);
    }

    @Nullable
    @SuppressWarnings("deprecation")
    private static String installer(final Context context) {
        final var pm = context.getPackageManager();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return pm.getInstallSourceInfo(context.getPackageName()).getInstallingPackageName();
            }
            return pm.getInstallerPackageName(context.getPackageName());
        } catch (final Exception e) {
            return null;
        }
    }

    @SuppressLint("MissingPermission") // checked at the start of the method
    private static boolean notify(final Context context, @Nullable final String versionName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            context.getString(R.string.chatrix_update_channel),
                            NotificationManager.IMPORTANCE_DEFAULT);
            context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        final PendingIntent open =
                PendingIntent.getActivity(
                        context,
                        NOTIFICATION_ID,
                        new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        final var notification =
                new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_app_icon_notification)
                        .setColor(ContextCompat.getColor(context, R.color.chatrix_logo))
                        .setContentTitle(
                                versionName == null
                                        ? context.getString(R.string.chatrix_update_channel)
                                        : context.getString(
                                                R.string.chatrix_update_available, versionName))
                        .setContentText(context.getString(R.string.chatrix_update_tap))
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build();
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification);
        return true;
    }
}
