package eu.siacs.conversations.services;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.utils.PhoneHelper;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.manager.PushNotificationManager;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class PushManagementService {

    protected static final ScheduledExecutorService FUTURE_TIMEOUT_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor();

    public static final String ACTION_REGISTER = "com.google.android.c2dm.intent.REGISTER";
    public static final String ACTION_REGISTRATION = "com.google.android.c2dm.intent.REGISTRATION";
    public static final String ACTION_RECEIVE = "com.google.android.c2dm.intent.RECEIVE";
    public static final String PACKAGE_NAME_GMS = "com.google.android.gms";

    protected final Context context;

    public PushManagementService(final Context service) {
        this.context = service;
    }

    private Jid getAppServer() {
        return Jid.of(context.getString(R.string.app_server));
    }

    public void registerPushTokenOnServer(final Account account) {
        final var connection = account.getXmppConnection();
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": has push support");
        final var fcmTokenFuture = retrieveFcmInstanceTokenTiny();
        final var fcmTokenTimeoutFuture =
                Futures.withTimeout(
                        fcmTokenFuture, Duration.ofSeconds(30L), FUTURE_TIMEOUT_EXECUTOR);
        final var future =
                Futures.transformAsync(
                        fcmTokenTimeoutFuture,
                        fcmToken -> {
                            final var pushManager =
                                    connection.getManager(PushNotificationManager.class);
                            Log.d(Config.LOGTAG, "FCM Token: " + fcmToken);
                            final var androidId = PhoneHelper.getAndroidId(context);
                            final var appServer = getAppServer();
                            return pushManager.registerAndEnable(appServer, fcmToken, androidId);
                        },
                        MoreExecutors.directExecutor());
        Futures.addCallback(
                future,
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(Void result) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid() + ": successfully enabled push");
                    }

                    @Override
                    public void onFailure(@NonNull Throwable t) {
                        Log.d(Config.LOGTAG, "could not register for push", t);
                    }
                },
                MoreExecutors.directExecutor());
    }

    private @NonNull ListenableFuture<String> retrieveFcmInstanceTokenTiny() {
        final var intent = new Intent(ACTION_REGISTER);
        intent.setPackage(PACKAGE_NAME_GMS);
        final var handler = new RegistrationMessageHandler(Looper.getMainLooper());
        final var connection =
                new RegistrationServiceConnection(
                        context, context.getString(R.string.gcm_defaultSenderId), handler);
        handler.endpointFuture.addListener(connection::unbind, MoreExecutors.directExecutor());
        try {
            if (context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                return handler.endpointFuture;
            } else {
                return Futures.immediateFailedFuture(
                        new IllegalStateException("Could not bind to Play Services"));
            }
        } catch (final SecurityException e) {
            return Futures.immediateFailedFuture(e);
        }
    }

    public boolean available(final Account account) {
        final XmppConnection connection = account.getXmppConnection();
        return connection != null
                && connection.getFeatures().sm()
                && connection.getManager(PushNotificationManager.class).hasFeature()
                && playServicesAvailable();
    }

    private boolean playServicesAvailable() {
        final var intent = new Intent(ACTION_REGISTER);
        intent.setPackage(PACKAGE_NAME_GMS);
        final var packageManager = context.getPackageManager();
        final var resolveInfo =
                packageManager.resolveService(intent, PackageManager.GET_RESOLVED_FILTER);
        return resolveInfo != null;
    }

    public static boolean isStub() {
        return false;
    }

    private static class RegistrationServiceConnection implements ServiceConnection {

        private final Context context;
        private final String sender;
        private final RegistrationMessageHandler handler;
        private final AtomicBoolean unbound = new AtomicBoolean(false);

        private RegistrationServiceConnection(
                final Context context,
                final String sender,
                final RegistrationMessageHandler handler) {
            this.context = context;
            this.sender = sender;
            this.handler = handler;
        }

        @Override
        public void onServiceConnected(final ComponentName name, final IBinder service) {
            final var data = new Bundle();
            data.putString("sender", sender);
            data.putString("subscription", sender);
            data.putString("subtype", sender);
            data.putString("scope", "GCM");
            final var request = new Bundle();
            request.putString("pkg", context.getPackageName());
            request.putBoolean("oneWay", false);
            request.putBundle("data", data);
            final var message = Message.obtain();
            message.what = 1;
            message.arg1 = 1;
            message.replyTo = new Messenger(handler);
            message.setData(request);
            try {
                new Messenger(service).send(message);
            } catch (final RemoteException e) {
                handler.endpointFuture.setException(e);
            }
        }

        @Override
        public void onServiceDisconnected(final ComponentName name) {
            handler.endpointFuture.setException(
                    new IllegalStateException("Play Services disconnected"));
        }

        @Override
        public void onBindingDied(final ComponentName name) {
            handler.endpointFuture.setException(
                    new IllegalStateException("Play Services binding died"));
        }

        @Override
        public void onNullBinding(final ComponentName name) {
            handler.endpointFuture.setException(
                    new IllegalStateException("Play Services returned null binding"));
        }

        private void unbind() {
            if (unbound.compareAndSet(false, true)) {
                try {
                    context.unbindService(this);
                } catch (final IllegalArgumentException e) {
                    Log.d(Config.LOGTAG, "could not unbind from Play Services", e);
                }
            }
        }
    }

    private static class RegistrationMessageHandler extends Handler {

        private final SettableFuture<String> endpointFuture = SettableFuture.create();

        public RegistrationMessageHandler(final Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@androidx.annotation.NonNull final Message message) {
            final var response = message.getData();
            if (response.getBoolean("ack", false)) {
                return;
            }
            if (response.getBoolean("unsupported", false)) {
                endpointFuture.setException(
                        new IllegalStateException("Play Services: unsupported request"));
                return;
            }
            final var data = response.getBundle("data");
            if (data == null) {
                endpointFuture.setException(
                        new IllegalStateException("Response did not contain data"));
                return;
            }
            final var error = data.getString("error");
            if (error != null) {
                endpointFuture.setException(
                        new IllegalStateException(String.format("Firebase error %s", error)));
                return;
            }
            final String registrationId = stripIdPrefix(data.getString("registration_id"));
            if (Strings.isNullOrEmpty(registrationId)) {
                endpointFuture.setException(
                        new IllegalStateException("Response did not contain registration id"));
                return;
            }
            endpointFuture.set(registrationId);
        }

        private static @Nullable String stripIdPrefix(@Nullable final String registrationId) {
            if (registrationId == null || !registrationId.startsWith("|ID|")) {
                return registrationId;
            }
            final int end = registrationId.indexOf('|', 4);
            if (end < 0) {
                return registrationId;
            }
            return registrationId.substring(end + 1);
        }
    }
}
