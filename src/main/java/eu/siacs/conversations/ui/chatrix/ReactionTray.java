package eu.siacs.conversations.ui.chatrix;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import eu.siacs.conversations.Config;
import java.util.Objects;

/**
 * Chatrix.One: the tray, not memory, decides whether the reaction summary may go (ultrareview
 * 2026-11-26: after Android killed the app, its reaction notifications stayed in the tray while the
 * in-memory list was empty, so opening a chat cleared nothing; and cancelling the summary, which
 * removes the whole group, could take other chats' unread reactions with it).
 */
public final class ReactionTray {

    private ReactionTray() {}

    /**
     * True if the tray shows a notification of this group other than its summary and the one with
     * the given tag and id (just cancelled: Android removes it a moment later). True as well if the
     * tray cannot be read: a silent summary left over is better than losing other chats' reactions.
     */
    public static boolean showsOthers(
            final Context context, final String group, final String exceptTag, final int exceptId) {
        final StatusBarNotification[] active;
        try {
            final NotificationManager manager = context.getSystemService(NotificationManager.class);
            active = manager == null ? null : manager.getActiveNotifications();
        } catch (final RuntimeException e) {
            Log.d(Config.LOGTAG, "could not read the active notifications", e);
            return true;
        }
        if (active == null) {
            return true;
        }
        for (final StatusBarNotification shown : active) {
            final Notification notification = shown == null ? null : shown.getNotification();
            if (notification == null || !group.equals(notification.getGroup())) {
                continue;
            }
            if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
                continue;
            }
            if (shown.getId() == exceptId && Objects.equals(exceptTag, shown.getTag())) {
                continue;
            }
            return true;
        }
        return false;
    }
}
